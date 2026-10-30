package imehc.recoow.core.location

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class TrackRecordingService : Service() {
    @Inject lateinit var runtime: TrackRecordingRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var commanded = false
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.track_channel), NotificationManager.IMPORTANCE_LOW))
        val notice = notification(runtime.state.value)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTICE, notice)
        } catch (_: SecurityException) { stopSelf(); return }
        scope.launch { runtime.state.collectLatest { state ->
            if (state.running) getSystemService(NotificationManager::class.java).notify(NOTICE, notification(state))
            else if (commanded && !state.busy) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            when (intent?.action) {
                START -> runtime.start(intent.getStringExtra("name").orEmpty(), intent.getIntExtra("accuracy", 10))
                PAUSE -> runtime.pause()
                FINISH -> runtime.finish()
            }
            commanded = true
            if (!runtime.state.value.running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); runtime.serviceDestroyed(); super.onDestroy() }
    private fun notification(state: RecordingState): Notification {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("recoow://tool/locationTracker")).setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_track_recording).setOngoing(true).setOnlyAlertOnce(true)
            .setContentTitle(state.current?.name ?: getString(R.string.track_channel))
            .setContentText(if (state.problem == RecordingProblem.SAVE) getString(R.string.track_save_failed) else getString(R.string.track_recording_duration, state.duration))
            .setContentIntent(PendingIntent.getActivity(this, 0, link, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        for ((action, label) in listOf(PAUSE to R.string.track_pause, FINISH to R.string.track_finish)) {
            val pending = PendingIntent.getService(this, 0, Intent(this, TrackRecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, getString(label), pending).build())
        }
        return builder.build()
    }
    companion object {
        const val START = "imehc.recoow.tracks.START"; const val PAUSE = "imehc.recoow.tracks.PAUSE"; const val FINISH = "imehc.recoow.tracks.FINISH"
        private const val CHANNEL = "track-recording"; private const val NOTICE = 8431
    }
}
