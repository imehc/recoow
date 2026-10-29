package imehc.recoow.core.data.records

import androidx.room.withTransaction
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.EntityContracts
import imehc.recoow.core.database.generated.RecoowDatabase
import java.security.MessageDigest
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Durable receive/apply primitives. HTTP, bootstrap paging and conflict resolution live in sync. */
class ReceivedRecordStore(private val host: DatabaseHost, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    suspend fun attach(target: SyncTarget) = host.guarded("attach-dataset") { db, _ ->
        db.withTransaction {
            require(target.scope.isNotBlank() && target.accountId.isNotBlank() && target.generation.isNotBlank())
            val current = db.records().activeScope()
            check(current == null || current == target.scope) { "Dataset replacement requires explicit initialization" }
            val existing = db.records().target(target.scope)
            if (existing == null) db.records().put(target) else {
                require(existing.connectionId == target.connectionId && existing.accountId == target.accountId && existing.generation == target.generation)
            }
            db.records().put(DatasetState(activeScope = target.scope))
        }
    }

    suspend fun receivePage(scope: String, session: Long, key: String, body: ByteArray, envelopes: List<String>, nextCursor: Long) =
        host.access(session) { db ->
            val target = activeTarget(db, scope)
            val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            val previous = db.records().pageHash(scope, key)
            if (previous != null) {
                require(previous == hash)
                if (db.records().pageReceived(scope, key) == true) return@access
            } else db.records().receive(SyncReceivedPage(scope, key, body, hash))
            // Raw bytes survive even if envelope parsing/validation fails below.
            db.withTransaction {
            require(nextCursor >= target.receivedCursor)
            for (raw in envelopes) {
                val envelope = Json.parseToJsonElement(raw).jsonObject
                val version = envelope.getValue("version").jsonPrimitive.long
                require(version in 1..nextCursor)
                val existing = db.records().receivedEnvelope(scope, version)
                if (existing != null) { require(existing == raw); continue }
                require(version > target.appliedCursor) { "Unexpected row behind the applied cursor" }
                db.records().receive(SyncInbox(scope, version, envelope.getValue("table").jsonPrimitive.content,
                    envelope.getValue("id").jsonPrimitive.content, raw))
            }
            db.records().put(target.copy(receivedCursor = nextCursor))
            db.records().markPageReceived(scope, key)
        } }

    suspend fun applyPending(scope: String, session: Long): Int = host.access(session) { db ->
        activeTarget(db, scope)
        var applied = 0
        do {
            var progress = false
            for (row in db.records().inbox(scope)) {
                try {
                    val success = db.withTransaction {
                        if (!apply(db, row)) return@withTransaction false
                        db.records().applied(scope, row.version)
                        advanceCursor(db, scope)
                        true
                    }
                    if (success) { progress = true; applied++ }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    db.records().failed(scope, row.version, error.message ?: error.javaClass.simpleName)
                }
            }
        } while (progress)
        db.withTransaction { advanceCursor(db, scope) }
        applied
    }

    private suspend fun apply(db: RecoowDatabase, row: SyncInbox): Boolean {
        val contract = EntityContracts.registry.entity(row.tableName)
        val envelope = Json.parseToJsonElement(row.envelopeJson).jsonObject
        val dataVersion = envelope.getValue("dataVersion").jsonPrimitive.int
        require(dataVersion in contract.minimumVersion..contract.version) { "Unsupported dataVersion" }
        val updatedAt = envelope.getValue("updatedAtMs").jsonPrimitive.long
        val device = envelope.getValue("deviceId").jsonPrimitive.content
        val deleted = envelope["deletedAtMs"]?.takeIf { it != JsonNull }
        val previous = db.records().shadow(row.scope, row.tableName, row.entityId)
        if (previous != null && previous.version >= row.version) return true
        val old = db.records().snapshot(row.tableName, row.entityId)
        val raw = envelope["snapshot"]?.takeIf { it != JsonNull }?.jsonObject
        require(raw != null || deleted != null) { "Missing row snapshot" }
        // Existing-row tombstones may omit the business snapshot. iOS only replays bill effects.
        val materialized = if (deleted != null && old != null) {
            Json.parseToJsonElement(old.json).jsonObject.toMutableMap().apply {
                if (row.tableName == "bills") raw?.get("stored_value_effects")?.let { put("stored_value_effects", it) }
            }
        } else raw?.toMutableMap()
        val incoming = materialized?.apply {
            put("id", JsonPrimitive(row.entityId)); put("updated_at", JsonPrimitive(updatedAt))
            put("device_id", JsonPrimitive(device)); put("deleted_at", deleted ?: JsonNull)
        }?.let { contract.normalize(JsonObject(it), dataVersion) }
        // Local edits remain visible; the remote snapshot is retained for explicit reconciliation.
        if (db.records().pending(row.scope, row.tableName, row.entityId) == null) {
            val fields = old?.json?.let { Json.parseToJsonElement(it).jsonObject }.orEmpty().toMutableMap()
            incoming?.let { fields.putAll(it) }
            if (fields.isNotEmpty()) {
                fields["id"] = JsonPrimitive(row.entityId)
                fields["updated_at"] = JsonPrimitive(updatedAt)
                fields["device_id"] = JsonPrimitive(device)
                fields["deleted_at"] = deleted ?: JsonNull
                val snapshot = contract.normalize(JsonObject(fields), contract.version)
                for (dependency in contract.dependencies) {
                    val id = snapshot[dependency.field]?.takeIf { it != JsonNull }?.jsonPrimitive?.content ?: continue
                    if (!PhysicalRecords.exists(db.openHelper.writableDatabase, dependency.table, id)) {
                        db.records().failed(row.scope, row.version, "Waiting for ${dependency.table}:$id")
                        return false
                    }
                }
                PhysicalRecords.write(db.openHelper.writableDatabase, contract, snapshot, row.version, local = false)
                db.records().put(EntitySnapshot(row.tableName, row.entityId, contract.version, snapshot.toString()))
                AggregateProjection.refresh(db, setOf(row.tableName), zone(), row.scope)
            }
            RoomRecordRepository.queueNotification(db, row.tableName, row.entityId, row.version)
        }
        db.records().put(SyncRow(row.scope, row.tableName, row.entityId, row.version, row.envelopeJson))
        return true
    }

    private suspend fun activeTarget(db: RecoowDatabase, scope: String): SyncTarget {
        check(db.records().activeScope() == scope) { "Inactive sync scope" }
        return requireNotNull(db.records().target(scope))
    }

    private suspend fun advanceCursor(db: RecoowDatabase, scope: String) {
        val target = activeTarget(db, scope)
        val firstPending = db.records().inbox(scope).firstOrNull()?.version
        val cursor = minOf(target.receivedCursor, firstPending?.minus(1) ?: target.receivedCursor)
        db.records().put(target.copy(appliedCursor = maxOf(target.appliedCursor, cursor)))
    }
}
