package imehc.recoow.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Query("SELECT * FROM entity_snapshots WHERE tableName = :table AND entityId = :id")
    suspend fun snapshot(table: String, id: String): EntitySnapshot?
    @Query("SELECT * FROM entity_snapshots WHERE tableName = :table ORDER BY entityId")
    suspend fun snapshots(table: String): List<EntitySnapshot>
    @Query("SELECT * FROM entity_snapshots WHERE tableName = :table ORDER BY entityId")
    fun observe(table: String): Flow<List<EntitySnapshot>>
    @Upsert suspend fun put(row: EntitySnapshot)
    @Insert suspend fun log(change: LocalChange): Long
    @Insert suspend fun enqueue(row: SyncOutbox)
    @Upsert suspend fun put(target: SyncTarget)
    @Upsert suspend fun put(state: DatasetState)
    @Upsert suspend fun put(row: SyncRow)
    @Insert suspend fun receive(row: SyncInbox)
    @Insert suspend fun receive(page: SyncReceivedPage)
    @Upsert suspend fun notify(job: NotificationJob)
    @Query("SELECT * FROM sync_targets WHERE scope = :scope") suspend fun target(scope: String): SyncTarget?
    @Query("SELECT activeScope FROM dataset_state WHERE id = 1") suspend fun activeScope(): String?
    @Query("SELECT * FROM sync_rows WHERE scope = :scope AND tableName = :table AND entityId = :id")
    suspend fun shadow(scope: String, table: String, id: String): SyncRow?
    @Query("SELECT * FROM sync_outbox WHERE scope = :scope AND tableName = :table AND entityId = :id AND status IN ('pending','conflict') ORDER BY localChangeId DESC LIMIT 1")
    suspend fun pending(scope: String, table: String, id: String): SyncOutbox?
    @Query("SELECT * FROM sync_inbox WHERE scope = :scope AND applied = 0 ORDER BY version")
    suspend fun inbox(scope: String): List<SyncInbox>
    @Query("UPDATE sync_inbox SET applied = 1, lastError = NULL WHERE scope = :scope AND version = :version")
    suspend fun applied(scope: String, version: Long)
    @Query("UPDATE sync_inbox SET lastError = :error WHERE scope = :scope AND version = :version")
    suspend fun failed(scope: String, version: Long, error: String)
    @Query("SELECT sha256 FROM sync_received_pages WHERE scope = :scope AND pageKey = :key")
    suspend fun pageHash(scope: String, key: String): String?
    @Query("SELECT received FROM sync_received_pages WHERE scope = :scope AND pageKey = :key")
    suspend fun pageReceived(scope: String, key: String): Boolean?
    @Query("UPDATE sync_received_pages SET received = 1 WHERE scope = :scope AND pageKey = :key")
    suspend fun markPageReceived(scope: String, key: String)
    @Query("SELECT envelopeJson FROM sync_inbox WHERE scope = :scope AND version = :version")
    suspend fun receivedEnvelope(scope: String, version: Long): String?
}
