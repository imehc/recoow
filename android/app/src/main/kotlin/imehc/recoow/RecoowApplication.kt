package imehc.recoow

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import imehc.recoow.core.sync.SyncRuntime
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

@HiltAndroidApp
class RecoowApplication : Application() {
    @Inject lateinit var sync: SyncRuntime
    @Inject lateinit var reminders: imehc.recoow.core.notifications.ReminderNotificationRuntime
    @Inject lateinit var data: imehc.recoow.data.DataManagementCoordinator
    @Inject lateinit var preferences: imehc.recoow.core.domain.preferences.PreferencesRepository
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                data.recover(); sync.start(); reminders.start()
                preferences.preferences.collectLatest { value ->
                    withContext(Dispatchers.IO) { imehc.recoow.widget.QuickToolWidget.renderWithPreferences(this@RecoowApplication, imehc.recoow.widget.QuickToolWidget.ids(this@RecoowApplication), value) }
                }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep failed recovery files intact; database reads expose the error. */ }
        }
    }
}
