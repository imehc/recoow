package imehc.recoow.core.data.records

import androidx.room.withTransaction
import imehc.recoow.core.database.*
import imehc.recoow.core.database.contract.ContractViolation
import imehc.recoow.core.database.generated.EntityContracts
import imehc.recoow.core.database.generated.RecoowDatabase
import imehc.recoow.core.domain.records.RecordChange
import imehc.recoow.core.domain.records.RecordRepository
import imehc.recoow.core.domain.records.StoredRecord
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*

class RoomRecordRepository(private val host: DatabaseHost, private val zone: () -> ZoneId = ZoneId::systemDefault) : RecordRepository {
    override fun observe(table: String) = host.observe { database ->
        EntityContracts.registry.entity(table)
        database.records().observe(table).map { rows -> rows.map { it.record() } }
    }
    override suspend fun find(table: String, id: String): StoredRecord? = host.access { database ->
        EntityContracts.registry.entity(table)
        database.records().snapshot(table, id)?.record()
    }

    override suspend fun save(changes: List<RecordChange>) = host.access { database ->
        database.withTransaction { saveInTransaction(database, changes) }
    }

    /** The caller owns DatabaseHost and the Room transaction; never reenter the host gate here. */
    internal suspend fun saveInTransaction(database: RecoowDatabase, changes: List<RecordChange>, inheritedMedia: Map<String, JsonObject> = emptyMap()) {
        check(database.inTransaction())
        val dao = database.records()
        val scope = dao.activeScope()
        for (change in changes) {
            require(change.id.isNotBlank() && change.deviceId.isNotBlank() && change.updatedAtMillis > 0)
            val contract = EntityContracts.registry.entity(change.table)
            val previous = dao.snapshot(change.table, change.id)?.json?.let { Json.parseToJsonElement(it).jsonObject }
            val patch = Json.parseToJsonElement(change.fieldsJson).jsonObject
            val allowed = contract.fields.filter { !it.derived && !it.binary && it.name !in SYSTEM_FIELDS }.map { it.name }.toSet()
            if (!allowed.containsAll(patch.keys)) throw ContractViolation("Invalid local fields in ${change.table}")
            require(!change.deleted || previous != null) { "Cannot delete a missing record" }
            val fields = previous.orEmpty().toMutableMap().apply {
                inheritedMedia[change.id]?.let { media ->
                    require(previous == null) { "Only a new record can inherit media" }
                    for ((field, value) in media) {
                        require(contract.fields.any { it.name == field && it.binary })
                        if (value != JsonNull) MediaReference.parse(value)
                    }
                    put("_media", media)
                }
                putAll(patch)
                put("id", JsonPrimitive(change.id))
                putIfAbsent("created_at", JsonPrimitive(change.updatedAtMillis))
                put("updated_at", JsonPrimitive(change.updatedAtMillis))
                put("device_id", JsonPrimitive(change.deviceId))
                put("deleted_at", if (change.deleted) JsonPrimitive(change.updatedAtMillis) else JsonNull)
            }
            val snapshot = MediaObjects(host.mediaDirectory).captureSnapshot(contract,
                contract.normalize(JsonObject(fields), contract.version), change.binaryFields)
            val version = scope?.let { dao.shadow(it, change.table, change.id)?.version }
            PhysicalRecords.write(database.openHelper.writableDatabase, contract, snapshot, version, local = true, change.binaryFields)
            dao.put(EntitySnapshot(change.table, change.id, contract.version, snapshot.toString()))
            val operation = if (change.deleted) "delete" else "update"
            val origin = host.clientSource?.origin(change.deviceId)
            dao.put(RecordOrigin(change.table, change.id, change.deviceId, change.updatedAtMillis, origin))
            val mutation = UUID.randomUUID().toString()
            val localId = dao.log(LocalChange(mutationId = mutation, tableName = change.table, entityId = change.id,
                operation = operation, dataVersion = contract.version, payloadJson = snapshot.toString(), changedAt = change.updatedAtMillis))
            if (scope != null) {
                val predecessor = dao.pending(scope, change.table, change.id)?.mutationId
                dao.enqueue(SyncOutbox(scope, mutation, change.table, change.id, operation, contract.version,
                    snapshot.toString(), version ?: 0, localId, predecessorId = predecessor, originJson = origin))
            }
            queueNotification(database, change.table, change.id, change.updatedAtMillis)
        }
        AggregateProjection.refresh(database, changes.map { it.table }.toSet(), zone())
    }

    companion object {
        private val SYSTEM_FIELDS = setOf("id", "created_at", "updated_at", "deleted_at", "device_id", "server_version", "sync_status")
        internal fun EntitySnapshot.record() = StoredRecord(tableName, entityId, dataVersion, json)
        internal suspend fun queueNotification(db: RecoowDatabase, table: String, id: String, version: Long) {
            if (table == "reminders" || table == "anniversaries") db.records().notify(NotificationJob(table, id, version))
        }
    }
}
