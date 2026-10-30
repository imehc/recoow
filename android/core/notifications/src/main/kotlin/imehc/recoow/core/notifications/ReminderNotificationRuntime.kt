package imehc.recoow.core.notifications

import android.app.*
import android.content.*
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import dagger.Binds
import dagger.hilt.EntryPoint
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import imehc.recoow.core.database.DatabaseHost
import imehc.recoow.core.domain.media.MediaLocator
import imehc.recoow.core.domain.media.MediaRepository
import imehc.recoow.core.domain.reminders.*
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ReminderNotificationRuntime @Inject constructor(@param:ApplicationContext private val context: Context,
    private val repository: RemindersRepository, private val media: MediaRepository, private val host: DatabaseHost) : ReminderNotifications, DefaultLifecycleObserver {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val ledger = context.getSharedPreferences("reminder-alarms", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(ReminderNotificationState())
    override val state = mutable.asStateFlow()
    private var started = false
    override suspend fun refreshAccess() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val allowed = manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
        mutable.update { it.copy(allowed = allowed, exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) }
    }
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminder-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderReconcileWorker>(15, TimeUnit.MINUTES).build())
        scope.launch {
            host.observe { it.records().observeNotifications() }.distinctUntilChanged().debounce(250).collect { requestReconcile() }
        }
        requestReconcile()
    }
    override fun onStart(owner: LifecycleOwner) { requestReconcile() }
    override fun requestReconcile() {
        WorkManager.getInstance(context).enqueueUniqueWork("reminder-reconcile", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ReminderReconcileWorker>().build())
    }
    @android.annotation.SuppressLint("ScheduleExactAlarm")
    suspend fun reconcile(): Boolean = lock.withLock {
        refreshAccess()
        val token = host.sessionToken()
        val jobs = host.access(token) { it.records().notifications().filter { job -> job.tableName == "reminders" } }
        val records = repository.reminders.first()
        val clock = System.currentTimeMillis(); val zone = ZoneId.systemDefault()
        val desired = if (state.value.allowed) records.filter { it.enabled }.flatMap { r ->
            // One next occurrence per task; delivery/check-in/periodic recovery replenishes it.
            r.upcoming(clock, zone, 1).flatMap { occurrence ->
                val day = Instant.ofEpochMilli(occurrence).atZone(zone).toLocalDate().toString()
                listOf(AlarmKey(r.id, day, "main", occurrence), AlarmKey(r.id, day, "lead", occurrence - r.lead * 60_000L))
                    .filter { (it.kind == "main" || r.lead > 0) && it.at > clock && ledger.getLong("sent:${it.key}", Long.MIN_VALUE) != it.at }
            }
        }.associateBy { it.key } else emptyMap()
        val old = ledger.getStringSet("scheduled", emptySet()).orEmpty()
        for (key in old - desired.keys) alarms.cancel(pending(key))
        // Keep delivered reminders visible until the task/date is no longer actionable.
        manager.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { notification ->
            val parts = notification.tag?.let(Uri::parse)?.pathSegments.orEmpty()
            val record = records.firstOrNull { it.id == parts.getOrNull(0) }
            val day = parts.getOrNull(1)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            if (record == null || !record.enabled || record.paused || record.completed(zone) || day in record.dates)
                manager.cancel(notification.tag, notification.id)
        }
        val scheduled = mutableSetOf<String>()
        var success = true
        for ((key, alarm) in desired) {
            try {
                val pending = pending(key, alarm.at)
                if (state.value.exact) try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.at, pending) }
                    catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.at, pending) }
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.at, pending)
                scheduled += key
            } catch (_: Exception) { success = false }
        }
        // Recovery retries desired schedules from current records; business data is unchanged.
        check(ledger.edit().putStringSet("scheduled", scheduled).commit())
        if (success && state.value.allowed) host.access(token) { db -> jobs.forEach { db.records().delivered(it.tableName, it.entityId, it.version) } }
        mutable.update { it.copy(failed = !success) }
        success
    }
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun deliver(uri: Uri, scheduled: Long) = lock.withLock {
        val key = uri.toString()
        val parts = uri.pathSegments
        if (uri.scheme != "recoow-alarm" || uri.host != "reminders" || parts.size != 3 || ledger.getLong("sent:$key", Long.MIN_VALUE) == scheduled) return@withLock
        val r = repository.reminders.first().firstOrNull { it.id == parts[0] } ?: return@withLock
        val day = runCatching { java.time.LocalDate.parse(parts[1]) }.getOrNull() ?: return@withLock
        val zone = ZoneId.systemDefault(); val clock = System.currentTimeMillis()
        val occurrence = r.occurrence(day, zone) ?: return@withLock
        val expected = if (parts[2] == "lead" && r.lead > 0) occurrence - r.lead * 60_000L else if (parts[2] == "main") occurrence else return@withLock
        refreshAccess()
        if (!state.value.allowed || !r.enabled || r.paused || r.completed(zone) || day in r.dates || expected != scheduled || clock < scheduled || Instant.ofEpochMilli(clock).atZone(zone).toLocalDate() > day) return@withLock
        if (r.kind == ScheduleKind.CONTINUOUS && r.firstMissed(clock, zone) != null) return@withLock
        val click = Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("recoow").authority("tool").appendPath("reminders").appendPath(r.id).build())
            .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_reminder).setContentTitle(if (r.hasImage) r.title else "${r.icon} ${r.title}")
            .setContentText(r.note).setStyle(Notification.BigTextStyle().bigText(r.note)).setAutoCancel(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, click, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (parts[2] == "lead") content.setSubText(context.getString(R.string.lead_notice, r.lead))
        if (r.hasImage) try {
            val file = withTimeout(2_000) { media.resolve(MediaLocator(r.imageAssetId, "reminders", r.id)).file }
            file?.let {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(it.path, options)
                options.inJustDecodeBounds = false; options.inSampleSize = maxOf(1, maxOf(options.outWidth, options.outHeight) / 512)
                BitmapFactory.decodeFile(it.path, options)?.let { bitmap -> content.setLargeIcon(bitmap).setStyle(Notification.BigPictureStyle().bigPicture(bitmap).setSummaryText(r.note)) }
            }
        } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e } catch (_: Exception) { /* Text remains deliverable. */ }
        // Stable occurrence receipt prevents a reboot/replayed PendingIntent from alerting twice.
        check(ledger.edit().putLong("sent:$key", scheduled).commit())
        manager.notify(key, 0, content.build())
        // Bound old receipts while retaining a generous replay window.
        val cutoff = clock - TimeUnit.DAYS.toMillis(400)
        val edit = ledger.edit(); ledger.all.filter { it.key.startsWith("sent:") && (it.value as? Long ?: clock) < cutoff }.keys.forEach(edit::remove); edit.apply()
    }
    private fun pending(key: String, time: Long = 0) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderAlarmReceiver::class.java).setData(Uri.parse(key)).putExtra("scheduled", time), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private data class AlarmKey(val id: String, val day: String, val kind: String, val at: Long) {
        val key get() = Uri.Builder().scheme("recoow-alarm").authority("reminders").appendPath(id).appendPath(day).appendPath(kind).build().toString()
    }
    companion object { const val CHANNEL = "reminder-check-ins" }
}

@Module @InstallIn(SingletonComponent::class)
abstract class ReminderNotificationModule { @Binds abstract fun notifications(runtime: ReminderNotificationRuntime): ReminderNotifications }
@EntryPoint @InstallIn(SingletonComponent::class)
interface ReminderNotificationEntryPoint { fun runtime(): ReminderNotificationRuntime }
private fun runtime(context: Context) = EntryPointAccessors.fromApplication(context.applicationContext, ReminderNotificationEntryPoint::class.java).runtime()
class ReminderReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { if (runtime(applicationContext).reconcile()) Result.success() else Result.retry() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
}
class ReminderSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { runtime(context).requestReconcile() }
}
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { intent.data?.let { runtime(context).deliver(it, intent.getLongExtra("scheduled", 0)) } } }
            catch (_: Exception) { /* The recovery worker retries scheduling after transient failures. */ }
            finally { try { runtime(context).requestReconcile() } finally { result.finish() } }
        }
    }
}
