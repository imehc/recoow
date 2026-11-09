package imehc.recoow.core.sync

import androidx.room.withTransaction
import imehc.recoow.core.data.records.ReceivedRecordStore
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.EntityContracts
import imehc.recoow.core.database.generated.RecoowDatabase
import imehc.recoow.core.domain.sync.RecordConflict
import imehc.recoow.core.domain.sync.ConflictValue
import imehc.recoow.core.network.*
import java.util.UUID
import kotlinx.serialization.json.*

class AccountReplacementRequired : IllegalStateException("Back up and reset local data before connecting another account")
class CloudResetConfirmationRequired : IllegalStateException("Cloud dataset changed; explicit confirmation is required")
data class SyncSession(val connection: Credentials, val scope: String, val databaseToken: Long, val supportsOrigin: Boolean = false)
data class PreparedMutation(val row: SyncOutbox, val request: JsonObject)

class SyncStore(val host: DatabaseHost, private val received: ReceivedRecordStore, private val credentials: CredentialStore) {
    val contracts = EntityContracts.registry.entities.mapValues { it.value.version }
    private val orderedTables: List<String> = buildList {
        val remaining = contracts.keys.toMutableSet()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { table -> EntityContracts.registry.entity(table).dependencies.all { it.table in this } }
            check(ready.isNotEmpty()) { "Cyclic entity dependencies" }
            addAll(ready); remaining.removeAll(ready.toSet())
        }
    }

    suspend fun hasDatasetBinding(): Boolean = host.access { it.records().activeScope() != null }

    /** Renewing device credentials must not create another sync history for the same local dataset. */
    suspend fun alignConnection(connection: Credentials): Credentials = host.access { db ->
        val active = db.records().activeScope()?.let { db.records().target(it) } ?: return@access connection
        if (active.accountId != connection.account || active.connectionId.substringBefore(':') != connection.connectionId.substringBefore(':')) {
            throw AccountReplacementRequired()
        }
        connection.copy(connectionId = active.connectionId)
    }

    suspend fun prepare(connection: Credentials, capabilities: Capabilities, allowGenerationChange: Boolean = false): SyncSession {
        val scope = connection.connectionId + ":" + capabilities.generation
        val needsBackup = host.access { db ->
            val active = db.records().activeScope()?.let { db.records().target(it) }
            val sameAccount = active == null || active.accountId == connection.account &&
                active.connectionId.substringBefore(':') == connection.connectionId.substringBefore(':')
            if (!sameAccount) throw AccountReplacementRequired()
            if (active != null && active.generation != "backup" && active.generation != capabilities.generation && !allowGenerationChange) {
                throw CloudResetConfirmationRequired()
            }
            val target = db.records().target(scope)
            target == null || !target.seeded || active?.scope != scope || invalidCursor(target, capabilities)
        }
        if (needsBackup) host.guarded("prepare-sync-dataset") { db, _ ->
            db.withTransaction {
                val dao = db.records()
                val active = dao.activeScope()?.let { dao.target(it) }
                val sameAccount = active == null || active.accountId == connection.account &&
                    active.connectionId.substringBefore(':') == connection.connectionId.substringBefore(':')
                if (!sameAccount) throw AccountReplacementRequired()
                if (active != null && active.generation != "backup" && active.generation != capabilities.generation && !allowGenerationChange) {
                    throw CloudResetConfirmationRequired()
                }
                var target = dao.target(scope)
                if (target == null) target = SyncTarget(scope, connection.connectionId, connection.account, capabilities.generation,
                    bootstrapWatermark = capabilities.head)
                if (invalidCursor(target, capabilities)) {
                    reset(db, scope)
                    target = target.copy(receivedCursor = 0, appliedCursor = 0, bootstrapAfter = 0, bootstrapComplete = false,
                        bootstrapWatermark = capabilities.head, seeded = false, failureCount = 0, nextAttemptAt = 0)
                }
                dao.put(target); dao.put(DatasetState(activeScope = scope))
                if (!target.seeded) { seed(db, scope); dao.put(target.copy(seeded = true)) }
            }
        }
        return SyncSession(connection, scope, host.sessionToken(), capabilities.mutationOrigin)
    }

    private fun invalidCursor(target: SyncTarget, caps: Capabilities) = if (target.bootstrapComplete)
        target.receivedCursor !in caps.minCursor..caps.head else target.bootstrapWatermark !in caps.minCursor..caps.head

    private suspend fun reset(db: RecoowDatabase, scope: String) {
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET status = 'reset' WHERE scope = ? AND status IN ('pending','conflict')", arrayOf(scope))
        for (table in listOf("sync_rows", "sync_inbox", "sync_received_pages", "sync_media_jobs", "sync_conflicts")) {
            db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE scope = ?", arrayOf(scope))
        }
    }

    suspend fun restart(session: SyncSession, caps: Capabilities): SyncSession {
        if (caps.generation != session.scope.substringAfterLast(':')) return prepare(session.connection, caps)
        host.guarded("restart-sync-bootstrap") { db, _ -> db.withTransaction {
            check(credentials.load()?.connectionId == session.connection.connectionId && db.records().activeScope() == session.scope)
            reset(db, session.scope)
            val target = SyncTarget(session.scope, session.connection.connectionId, session.connection.account, caps.generation,
                bootstrapWatermark = caps.head, seeded = true)
            db.records().put(target); seed(db, session.scope)
        } }
        return session.copy(databaseToken = host.sessionToken(), supportsOrigin = caps.mutationOrigin)
    }

    private suspend fun seed(db: RecoowDatabase, scope: String) {
        for (table in orderedTables) for (record in db.records().snapshots(table)) {
            if (db.records().pending(scope, table, record.entityId) != null) continue
            val contract = EntityContracts.registry.entity(table)
            val snapshot = Json.parseToJsonElement(record.json).jsonObject
            val binary = mutableMapOf<String, ByteArray?>()
            for (field in contract.fields.filter { it.binary }) {
                // Preserve incoming references when the bytes have not downloaded yet.
                if (snapshot["_media"]?.jsonObject?.containsKey(field.name) == true || table == "media_assets" && snapshot["_media"]?.jsonObject?.containsKey("object") == true) continue
                binary[field.name] = PhysicalRecords.binary(db.openHelper.writableDatabase, table, record.entityId, field.name)
            }
            val payload = MediaObjects(host.mediaDirectory).captureSnapshot(contract, snapshot, binary)
            enqueue(db, scope, table, record.entityId, payload, base = 0, seed = true)
        }
    }

    private suspend fun enqueue(db: RecoowDatabase, scope: String, table: String, id: String, payload: JsonObject, base: Long, seed: Boolean = false): SyncOutbox {
        val mutation = UUID.randomUUID().toString()
        val operation = if (payload["deleted_at"] != null && payload["deleted_at"] != JsonNull) "delete" else "update"
        val version = contracts.getValue(table)
        // Initial inventory is not a new local edit. Zero identifies an unsent seed.
        val local = if (seed) 0L else db.records().log(LocalChange(mutationId = mutation, tableName = table, entityId = id, operation = operation,
            dataVersion = version, payloadJson = payload.toString(), changedAt = payload.integer("updated_at")))
        val origin = db.records().origin(table, id, payload.text("device_id"), payload.integer("updated_at"))
        val row = SyncOutbox(scope, mutation, table, id, operation, version, payload.toString(), base, local, originJson = origin)
        db.records().enqueue(row)
        return row
    }

    suspend fun <T> access(session: SyncSession, action: suspend (RecoowDatabase) -> T): T = host.access(session.databaseToken) { db ->
        check(credentials.load()?.connectionId == session.connection.connectionId && db.records().activeScope() == session.scope) { "Stale sync session" }
        action(db)
    }
    suspend fun target(session: SyncSession) = access(session) { requireNotNull(it.records().target(session.scope)) }

    suspend fun manifestSeeds(session: SyncSession, entries: List<JsonObject>): Map<Long, Pair<SyncOutbox, JsonObject>> = access(session) { db ->
        buildMap {
            for (entry in entries) {
                if (entry.flag("deleted")) continue
                val table = entry.text("table"); val id = entry.text("id")
                val contract = EntityContracts.registry.entity(table)
                if (entry.integer("dataVersion") != contract.version.toLong()) continue
                val row = db.records().manifestSeed(session.scope, table, id) ?: continue
                val snapshot = runCatching { manifestSnapshot(row) }.getOrNull() ?: continue
                val digest = runCatching { SyncSnapshotDigest.digest(snapshot) }.getOrNull() ?: continue
                if (digest == entry.getValue("digest").jsonPrimitive.content) put(entry.integer("version"), row to snapshot)
            }
        }
    }

    // Runs in the same transaction as inbox insertion and bootstrap progress.
    suspend fun matchManifestSeeds(db: RecoowDatabase, scope: String, page: JsonObject) {
        check(db.inTransaction())
        val entries = page.getValue("entries").jsonArray.map { it.jsonObject }.associateBy { it.integer("version") }
        for ((versionText, rawMutation) in page["matchedSeeds"]?.jsonObject.orEmpty()) {
            val entry = requireNotNull(entries[versionText.toLong()])
            val canonical = SyncProtocol.row(entry)
            require(canonical["deletedAtMs"] == JsonNull)
            val row = db.records().mutation(scope, rawMutation.jsonPrimitive.content) ?: continue
            if (row.status != "pending" || row.preparedJson != null || row.baseVersion != 0L || row.localChangeId != 0L) continue
            require(row.tableName == canonical.text("table") && row.entityId == canonical.text("id"))
            val local = manifestSnapshot(row)
            if (SyncSnapshotDigest.digest(local) != SyncSnapshotDigest.digest(canonical.getValue("snapshot").jsonObject)) continue
            db.records().put(row.copy(status = "matched", serverVersion = canonical.integer("version"), lastError = null))
            rebasePending(db, scope, row, canonical)
        }
    }

    private fun manifestSnapshot(row: SyncOutbox): JsonObject {
        val contract = EntityContracts.registry.entity(row.tableName)
        val raw = Json.parseToJsonElement(row.payloadJson).jsonObject
        val normalized = contract.normalize(raw,row.dataVersion)
        val derived = contract.fields.filter { it.derived }.map { it.name }.toSet()
        return JsonObject(normalized + raw.filterKeys { it in derived })
    }

    suspend fun pending(session: SyncSession, limit: Int): List<PreparedMutation> = access(session) { db -> db.withTransaction {
        val dao = db.records(); val rows = dao.outbox(session.scope)
        val available = rows.filter { row -> row.status == "pending" && rows.none { other ->
            other.tableName == row.tableName && other.entityId == row.entityId &&
                (other.status == "conflict" || other.status == "pending" && other.localChangeId < row.localChangeId)
        } }.sortedWith(compareBy({ orderedTables.indexOf(it.tableName) }, { it.localChangeId })).take(limit)
        available.map { row ->
            val request = row.preparedJson?.let { Json.parseToJsonElement(it).jsonObject } ?: run {
                val payload = Json.parseToJsonElement(row.payloadJson).jsonObject.toMutableMap()
                val shadow = dao.shadow(session.scope, row.tableName, row.entityId)?.envelopeJson?.let { Json.parseToJsonElement(it).jsonObject }
                val known = EntityContracts.registry.entity(row.tableName).fields.map { it.name }.toSet()
                for ((key, value) in shadow?.get("snapshot")?.takeIf { it != JsonNull }?.jsonObject.orEmpty()) {
                    if (key !in known && key !in payload) payload[key] = value
                }
                val prior = row.predecessorId?.let { dao.mutation(session.scope, it) }
                val base = if (prior?.status in setOf("applied", "duplicate", "matched")) requireNotNull(prior?.serverVersion) else row.baseVersion
                val normalized = EntityContracts.registry.entity(row.tableName).normalize(JsonObject(payload), row.dataVersion)
                buildJsonObject {
                    put("table", row.tableName); put("id", row.entityId); put("operation", row.operation)
                    put("mutationId", row.mutationId); put("baseVersion", base); put("dataVersion", row.dataVersion)
                    put("updatedAtMs", JsonObject(payload).integer("updated_at")); put("deviceId", JsonObject(payload).text("device_id"))
                    put("snapshot", normalized)
                    row.originJson?.let { if (session.supportsOrigin) put("origin", Json.parseToJsonElement(it)) }
                }.also { dao.put(row.copy(preparedJson = it.toString(), baseVersion = base)) }
            }
            require(session.supportsOrigin || "origin" !in request) { "Server does not support the frozen mutation source" }
            queueUploads(db, session.scope, row, request)
            PreparedMutation(row, request)
        }
    } }

    private suspend fun queueUploads(db: RecoowDatabase, scope: String, row: SyncOutbox, request: JsonObject) {
        if (row.operation == "delete") return
        val snapshot = request.getValue("snapshot").jsonObject
        val contract = EntityContracts.registry.entity(row.tableName)
        val references = snapshot["_media"]?.jsonObject.orEmpty()
        if (row.tableName == "media_assets") require(references["object"] != null && references["object"] != JsonNull) { "Missing media object" }
        val existing = db.records().media(scope, "upload")
        for ((field, raw) in references) {
            require(contract.fields.any { it.name == field && it.binary } || field == "object" && row.tableName == "media_assets")
            if (raw == JsonNull) continue
            val ref = MediaReference.parse(raw)
            if (existing.none { it.tableName == row.tableName && it.entityId == row.entityId && it.columnName == field && it.checksum == ref.checksum }) {
                db.records().put(SyncMediaJob(scope = scope, direction = "upload", objectKey = ref.key, checksum = ref.checksum,
                    size = ref.size, contentType = ref.contentType, tableName = row.tableName, entityId = row.entityId, columnName = field, version = 0))
            }
        }
    }

    suspend fun acknowledge(session: SyncSession, mutations: List<PreparedMutation>, receipts: List<Receipt>) = access(session) { db -> db.withTransaction {
        require(mutations.size == receipts.size)
        for ((mutation, receipt) in mutations.zip(receipts)) {
            val row = requireNotNull(db.records().mutation(session.scope, receipt.mutation))
            check(row.status == "pending" && row.preparedJson == mutation.request.toString())
            if (receipt.status !in setOf("applied", "duplicate", "conflict")) {
                db.records().put(row.copy(attemptCount = row.attemptCount + 1, lastError = receipt.status)); continue
            }
            val canonical = requireNotNull(receipt.current)
            db.records().put(row.copy(status = receipt.status, serverVersion = receipt.version, lastError = null))
            if (receipt.status == "conflict") db.records().put(SyncConflict(session.scope, row.mutationId, row.tableName, row.entityId,
                row.payloadJson, canonical.toString(), receipt.version, System.currentTimeMillis()))
            if (receipt.status in setOf("applied", "duplicate")) rebasePending(db, session.scope, row, canonical)
            val newer = db.records().shadow(session.scope, row.tableName, row.entityId)?.takeIf { it.version > receipt.version }
            val inbox = SyncInbox(session.scope, newer?.version ?: receipt.version, row.tableName, row.entityId, newer?.envelopeJson ?: canonical.toString())
            if (!received.apply(db, inbox, force = true)) {
                val existing = db.records().receivedEnvelope(session.scope, inbox.version)
                if (existing == null) db.records().receive(inbox) else require(existing == inbox.envelopeJson)
            }
        }
    } }

    private suspend fun rebasePending(db: RecoowDatabase, scope: String, acknowledged: SyncOutbox, canonical: JsonObject) {
        var rebased = canonical["snapshot"]?.takeIf { it != JsonNull }?.jsonObject ?: return
        rebased = JsonObject(rebased + ("deleted_at" to (canonical["deletedAtMs"] ?: JsonNull)))
        var base = Json.parseToJsonElement(acknowledged.payloadJson).jsonObject
        var predecessorId = acknowledged.mutationId
        val queued = db.records().outbox(scope).filter { it.tableName == acknowledged.tableName && it.entityId == acknowledged.entityId &&
            it.status == "pending" && it.localChangeId > acknowledged.localChangeId }
        for (row in queued) {
            if (row.predecessorId != predecessorId) return
            check(row.preparedJson == null) { "Cannot change a prepared mutation" }
            val next = Json.parseToJsonElement(row.payloadJson).jsonObject
            if (SyncSnapshotRebase.conflicts(row.tableName, base, next, rebased)) {
                db.records().put(row.copy(predecessorId = null, baseVersion = acknowledged.baseVersion))
                return
            }
            rebased = SyncSnapshotRebase.snapshot(row.tableName, base, next, rebased)
            db.records().put(row.copy(payloadJson = rebased.toString()))
            base = next
            predecessorId = row.mutationId
        }
        if (queued.isNotEmpty()) received.rebaseLocal(db, acknowledged.tableName, acknowledged.entityId, rebased, canonical.integer("version"))
    }

    suspend fun resolve(session: SyncSession, table: String, id: String, remoteVersion: Long, keepLocal: Boolean,
        expectedLocal: String? = null) = access(session) { db -> db.withTransaction {
        val dao = db.records()
        require(dao.conflicts(session.scope).any { it.tableName == table && it.entityId == id })
        val shadow = requireNotNull(dao.shadow(session.scope, table, id))
        check(shadow.version == remoteVersion) { "Conflict changed; refresh before choosing" }
        val local = requireNotNull(dao.snapshot(table, id)).json
        check(expectedLocal == null || Json.parseToJsonElement(expectedLocal) == Json.parseToJsonElement(local)) { "Local record changed; refresh before choosing" }
        val rows = dao.outbox(session.scope).filter { it.tableName == table && it.entityId == id && it.status in setOf("pending", "conflict") }
        val base = requireNotNull(rows.lastOrNull { it.status == "conflict" }).baseVersion
        for (row in rows) dao.put(row.copy(status = if (keepLocal) "resolved_local" else "resolved_remote"))
        val snapshot = Json.parseToJsonElement(local).jsonObject
        val row = enqueue(db, session.scope, table, id, snapshot, base)
        val normalized = EntityContracts.registry.entity(table).normalize(snapshot, row.dataVersion)
        val request = buildJsonObject {
            put("table", table); put("id", id); put("operation", row.operation)
            put("mutationId", row.mutationId); put("baseVersion", base); put("dataVersion", row.dataVersion)
            put("updatedAtMs", snapshot.integer("updated_at")); put("deviceId", snapshot.text("device_id"))
            row.originJson?.let { if (session.supportsOrigin) put("origin", Json.parseToJsonElement(it)) }
            put("snapshot", normalized); put("resolution", if (keepLocal) "local" else "remote"); put("expectedVersion", remoteVersion)
        }
        dao.put(row.copy(preparedJson = request.toString()))
        dao.clearConflicts(session.scope, table, id)
    } }

    suspend fun conflicts(session: SyncSession): List<RecordConflict> = access(session) { db ->
        db.records().conflicts(session.scope).distinctBy { it.tableName to it.entityId }.map { row ->
            val remote = requireNotNull(db.records().shadow(session.scope, row.tableName, row.entityId))
            val localJson = db.records().snapshot(row.tableName, row.entityId)?.json ?: row.localJson
            val local = Json.parseToJsonElement(localJson).jsonObject
            val envelope = Json.parseToJsonElement(remote.envelopeJson).jsonObject
            val snapshot = envelope["snapshot"]?.takeIf { it != JsonNull }?.jsonObject.orEmpty()
            fun display(value: JsonElement?, name: String): String {
                if (value == null || value == JsonNull) return "—"
                if (name.endsWith("amount_cents") && value is JsonPrimitive && value.longOrNull != null)
                    return "¥" + java.math.BigDecimal.valueOf(value.long).movePointLeft(2).setScale(2).toPlainString()
                return if (value is JsonPrimitive) value.content else value.toString()
            }
            val fields = EntityContracts.registry.entity(row.tableName).fields.filter {
                !it.binary && !it.derived && it.name !in setOf("id", "created_at", "updated_at", "device_id", "server_version", "deleted_at", "storage_key", "checksum", "stored_value_effects") && local[it.name] != snapshot[it.name]
            }.map { field -> ConflictValue(field.labels["zh"] ?: field.name, field.labels["en"] ?: field.name,
                display(local[field.name], field.name), display(snapshot[field.name], field.name)) }
            RecordConflict(row.tableName, row.entityId, localJson, remote.envelopeJson, remote.version, fields,
                local["deleted_at"] != null && local["deleted_at"] != JsonNull, envelope["deletedAtMs"]?.takeIf { it != JsonNull }?.jsonPrimitive?.long?.let { it > 0 } == true)
        }
    }

    suspend fun retry(session: SyncSession, failed: Boolean): Long = access(session) { db ->
        val target = requireNotNull(db.records().target(session.scope))
        val count = if (failed) target.failureCount + 1 else 0
        val delay = if (failed) minOf(3_600_000L, 5_000L * (1L shl count.coerceAtMost(10))) else 0
        db.records().put(target.copy(failureCount = count, nextAttemptAt = if (failed) System.currentTimeMillis() + delay else 0))
        delay
    }
}
