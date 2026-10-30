package imehc.recoow.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import imehc.recoow.core.data.backup.RoomDataManagement
import imehc.recoow.core.domain.backup.*
import imehc.recoow.core.domain.sync.SyncRepository
import imehc.recoow.core.location.TrackRecordingRuntime
import imehc.recoow.core.notifications.ReminderNotificationRuntime
import imehc.recoow.core.notifications.AnniversaryNotificationRuntime
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Singleton
class DataManagementCoordinator @Inject constructor(private val data: RoomDataManagement,
    private val sync: SyncRepository, private val tracks: TrackRecordingRuntime,
    private val reminders: ReminderNotificationRuntime, private val anniversaries: AnniversaryNotificationRuntime) : DataManagement {
    override suspend fun usage() = data.usage()
    override suspend fun export(openDestination: () -> OutputStream) = data.export(openDestination)
    override suspend fun preview(openSource: () -> InputStream) = data.preview(openSource)
    override fun discard(backup: PreparedBackup) = data.discard(backup)
    override suspend fun optimize() = sync.localMaintenance {
        tracks.prepareMaintenance()
        val reclaimed = data.optimize()
        withContext(NonCancellable) { tracks.databaseChanged() }
        reclaimed
    }
    override suspend fun import(backup: PreparedBackup, mode: ImportMode, scopes: Set<ImportScope>): Int =
        sync.localMaintenance {
            tracks.prepareMaintenance()
            val count = data.import(backup, mode, scopes)
            withContext(NonCancellable) { tracks.databaseChanged(); reminders.requestReconcile() }
            count
        }
    override suspend fun reset() {
        sync.localMaintenance(clearCredentials = true) { tracks.prepareMaintenance(); data.reset() }
        withContext(NonCancellable) { finishReset() }
    }
    /** A successful reset journals credential cleanup before publishing the empty dataset. */
    suspend fun recover() {
        if (data.resetPending()) {
            sync.localMaintenance(clearCredentials = true) { }
            finishReset()
        }
    }
    private suspend fun finishReset() {
        tracks.databaseChanged()
        reminders.resetLocalState(); anniversaries.resetLocalState()
        data.finishReset()
    }
}
@Module @InstallIn(SingletonComponent::class)
abstract class DataManagementModule {
    @Binds abstract fun dataManagement(coordinator: DataManagementCoordinator): DataManagement
}
