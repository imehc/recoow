package imehc.recoow.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Query("SELECT * FROM entity_snapshots WHERE tableName IN ('media_assets','media_attachments','reminders','bills','stored_items','decision_options','decision_choice_records')")
    fun observeMediaRecords(): Flow<List<EntitySnapshot>>
    @Query("SELECT COUNT(*) FROM sync_media_jobs")
    fun observeMediaJobs(): Flow<Int>
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

    @Upsert suspend fun put(row: SyncOutbox)
    @Upsert suspend fun put(row: SyncConflict)
    @Upsert suspend fun put(row: SyncMediaJob)
    @Query("SELECT * FROM sync_outbox WHERE scope = :scope ORDER BY localChangeId")
    suspend fun outbox(scope: String): List<SyncOutbox>
    @Query("SELECT * FROM sync_outbox WHERE scope = :scope AND mutationId = :id")
    suspend fun mutation(scope: String, id: String): SyncOutbox?
    @Query("SELECT * FROM sync_conflicts WHERE scope = :scope ORDER BY createdAt")
    suspend fun conflicts(scope: String): List<SyncConflict>
    @Query("DELETE FROM sync_conflicts WHERE scope = :scope AND tableName = :table AND entityId = :id")
    suspend fun clearConflicts(scope: String, table: String, id: String)
    @Query("SELECT * FROM sync_media_jobs WHERE scope = :scope AND direction = :direction ORDER BY id")
    suspend fun media(scope: String, direction: String): List<SyncMediaJob>
    @Query("SELECT * FROM sync_received_pages WHERE scope = :scope AND received = 0 ORDER BY pageKey")
    suspend fun unreceivedPages(scope: String): List<SyncReceivedPage>
    @Query("SELECT * FROM notification_jobs ORDER BY version")
    suspend fun notifications(): List<NotificationJob>
    @Query("DELETE FROM notification_jobs WHERE tableName = :table AND entityId = :id AND version = :version")
    suspend fun delivered(table: String, id: String, version: Long)
    @Query("SELECT COALESCE(MAX(id), 0) FROM local_changes")
    fun observeLocalChanges(): Flow<Long>
    @Query("DELETE FROM sync_received_pages WHERE scope = :scope AND pageKey = :key AND received = 1")
    suspend fun removeReceivedPage(scope: String, key: String)
}
