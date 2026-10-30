package imehc.recoow

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import imehc.recoow.core.sync.SyncRuntime
import javax.inject.Inject

@HiltAndroidApp
class RecoowApplication : Application() {
    @Inject lateinit var sync: SyncRuntime
    @Inject lateinit var reminders: imehc.recoow.core.notifications.ReminderNotificationRuntime
    override fun onCreate() { super.onCreate(); sync.start(); reminders.start() }
}
