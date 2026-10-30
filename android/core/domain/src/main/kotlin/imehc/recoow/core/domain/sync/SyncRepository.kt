package imehc.recoow.core.domain.sync

import kotlinx.coroutines.flow.StateFlow

enum class ConnectionMethod { CREATE, PAIR, RECOVER }
interface DeviceIdentity { val id: String }
enum class SyncProblem { CONNECTION, AUTHENTICATION, INCOMPATIBLE, STORAGE, RESET, ACCOUNT_REPLACEMENT, MEDIA, PENDING }
data class SyncConnection(val endpoint: String, val accountId: String, val serverDeviceId: String, val canPair: Boolean = false)
data class ConflictValue(val chineseLabel: String, val englishLabel: String, val local: String, val remote: String)
data class RecordConflict(val table: String, val id: String, val localSnapshot: String, val remoteSnapshot: String, val remoteVersion: Long,
    val values: List<ConflictValue> = emptyList(), val localDeleted: Boolean = false, val remoteDeleted: Boolean = false)
enum class SyncStage { IDLE, PREPARING, UPLOADING, DOWNLOADING, APPLYING }
data class SyncDevice(val id: String, val name: String, val owner: Boolean, val lastSeenAt: String)
data class SyncState(val loaded: Boolean = false, val connection: SyncConnection? = null, val running: Boolean = false,
    val pending: Int = 0, val conflicts: List<RecordConflict> = emptyList(), val waitingRows: Int = 0,
    val pendingMedia: Int = 0, val notificationJobs: Int = 0, val problem: SyncProblem? = null,
    val recoveryKey: String? = null, val pairingCode: String? = null,
    val enabled: Boolean = false, val lastSuccessAt: Long? = null, val stage: SyncStage = SyncStage.IDLE,
    val devices: List<SyncDevice> = emptyList())

interface SyncRepository {
    val state: StateFlow<SyncState>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun refreshDevices()
    suspend fun revokeDevice(id: String)
    suspend fun refresh()
    suspend fun connect(endpoint: String, method: ConnectionMethod, secret: String, deviceName: String, replaceLocal: Boolean = false)
    suspend fun disconnect()
    suspend fun sync(manual: Boolean = true): Boolean
    suspend fun resolve(table: String, id: String, remoteVersion: Long, keepLocal: Boolean)
    suspend fun acknowledgeRecoveryKey()
    suspend fun generatePairingCode()
    suspend fun dismissPairingCode()
}
