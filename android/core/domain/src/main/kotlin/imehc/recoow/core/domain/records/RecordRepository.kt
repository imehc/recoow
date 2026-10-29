package imehc.recoow.core.domain.records

import kotlinx.coroutines.flow.Flow

data class StoredRecord(val table: String, val id: String, val dataVersion: Int, val snapshotJson: String)
data class RecordChange(val table: String, val id: String, val fieldsJson: String,
    val updatedAtMillis: Long, val deviceId: String, val deleted: Boolean = false,
    val binaryFields: Map<String, ByteArray?> = emptyMap())

interface RecordRepository {
    fun observe(table: String): Flow<List<StoredRecord>>
    suspend fun find(table: String, id: String): StoredRecord?
    suspend fun save(changes: List<RecordChange>)
}
