package imehc.recoow.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// These tables are local persistence policy, never part of an entity's wire payload.
@Entity(tableName = "entity_snapshots", primaryKeys = ["tableName", "entityId"])
data class EntitySnapshot(val tableName: String, val entityId: String, val dataVersion: Int, val json: String)

@Entity(tableName = "local_changes", indices = [Index(value = ["mutationId"], unique = true)])
data class LocalChange(@PrimaryKey(autoGenerate = true) val id: Long = 0, val mutationId: String,
    val tableName: String, val entityId: String, val operation: String, val dataVersion: Int,
    val payloadJson: String, val changedAt: Long)

@Entity(tableName = "sync_targets")
data class SyncTarget(@PrimaryKey val scope: String, val connectionId: String, val accountId: String,
    val generation: String, val receivedCursor: Long = 0, val appliedCursor: Long = 0,
    val bootstrapWatermark: Long = 0, val bootstrapAfter: Long = 0, val bootstrapComplete: Boolean = false,
    val seeded: Boolean = false, val failureCount: Int = 0, val nextAttemptAt: Long = 0)

@Entity(tableName = "sync_outbox", primaryKeys = ["scope", "mutationId"],
    foreignKeys = [ForeignKey(entity = SyncTarget::class, parentColumns = ["scope"], childColumns = ["scope"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["scope", "status", "tableName", "entityId", "localChangeId"])])
data class SyncOutbox(val scope: String, val mutationId: String, val tableName: String, val entityId: String,
    val operation: String, val dataVersion: Int, val payloadJson: String, val baseVersion: Long,
    val localChangeId: Long, val predecessorId: String? = null, val preparedJson: String? = null,
    val status: String = "pending", val serverVersion: Long? = null, val attemptCount: Int = 0, val lastError: String? = null)

@Entity(tableName = "sync_rows", primaryKeys = ["scope", "tableName", "entityId"],
    foreignKeys = [ForeignKey(entity = SyncTarget::class, parentColumns = ["scope"], childColumns = ["scope"], onDelete = ForeignKey.CASCADE)])
data class SyncRow(val scope: String, val tableName: String, val entityId: String, val version: Long, val envelopeJson: String)

@Entity(tableName = "sync_inbox", primaryKeys = ["scope", "version"],
    foreignKeys = [ForeignKey(entity = SyncTarget::class, parentColumns = ["scope"], childColumns = ["scope"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["scope", "applied", "version"])])
data class SyncInbox(val scope: String, val version: Long, val tableName: String, val entityId: String,
    val envelopeJson: String, val applied: Boolean = false, val lastError: String? = null)

@Entity(tableName = "sync_received_pages", primaryKeys = ["scope", "pageKey"],
    foreignKeys = [ForeignKey(entity = SyncTarget::class, parentColumns = ["scope"], childColumns = ["scope"], onDelete = ForeignKey.CASCADE)])
data class SyncReceivedPage(val scope: String, val pageKey: String, val body: ByteArray, val sha256: String, val received: Boolean = false)

@Entity(tableName = "sync_conflicts", primaryKeys = ["scope", "mutationId"],
    foreignKeys = [ForeignKey(entity = SyncTarget::class, parentColumns = ["scope"], childColumns = ["scope"], onDelete = ForeignKey.CASCADE)])
data class SyncConflict(val scope: String, val mutationId: String, val tableName: String, val entityId: String,
    val localJson: String, val remoteJson: String, val remoteVersion: Long, val createdAt: Long)

@Entity(tableName = "sync_media_jobs", indices = [Index(value = ["scope", "direction", "tableName", "entityId", "columnName", "checksum"], unique = true)],
    foreignKeys = [ForeignKey(entity = SyncTarget::class, parentColumns = ["scope"], childColumns = ["scope"], onDelete = ForeignKey.CASCADE)])
data class SyncMediaJob(@PrimaryKey(autoGenerate = true) val id: Long = 0, val scope: String,
    val direction: String, val objectKey: String, val checksum: String, val size: Long, val contentType: String,
    val tableName: String, val entityId: String, val columnName: String, val version: Long,
    val storageKey: String? = null, val state: String = "pending", val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0, val lastError: String? = null)

@Entity(tableName = "notification_jobs", primaryKeys = ["tableName", "entityId"])
data class NotificationJob(val tableName: String, val entityId: String, val version: Long)

@Entity(tableName = "dataset_state")
data class DatasetState(@PrimaryKey val id: Int = 1, val activeScope: String? = null)
