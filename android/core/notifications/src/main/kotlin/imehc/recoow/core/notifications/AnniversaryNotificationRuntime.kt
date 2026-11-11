package imehc.recoow.core.notifications

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import imehc.recoow.core.calendar.IcuAnniversaryCalendar
import imehc.recoow.core.database.DatabaseHost
import imehc.recoow.core.domain.anniversaries.*
import imehc.recoow.core.domain.calendar.AnniversaryCalendar
import imehc.recoow.core.domain.reminders.ReminderNotificationState
import java.time.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 复用公共的恢复 Worker 与闹钟接收器；回执与通知渠道仍由各功能自己定义。 */
@Singleton
class AnniversaryNotificationRuntime @Inject constructor(@param:ApplicationContext private val context: Context,
    private val repository: AnniversariesRepository, private val calendar: AnniversaryCalendar, private val host: DatabaseHost) : AnniversaryNotifications {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val ledger = context.getSharedPreferences("anniversary-alarms", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val mutable = MutableStateFlow(ReminderNotificationState())
    override val state = mutable.asStateFlow()
    suspend fun resetLocalState() = lock.withLock {
        for (key in ledger.getStringSet("scheduled", emptySet()).orEmpty()) {
            val intent = pending(key); alarms.cancel(intent); intent.cancel()
        }
        manager.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { manager.cancel(it.tag, it.id) }
        check(ledger.edit().clear().commit())
        mutable.value = ReminderNotificationState()
    }
    override suspend fun refreshAccess() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.anniversary_channel), NotificationManager.IMPORTANCE_DEFAULT))
        mutable.update { it.copy(allowed = manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE,
            exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) }
    }
    override fun requestReconcile() {
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork("reminder-reconcile", androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<ReminderReconcileWorker>().build())
    }
    @android.annotation.SuppressLint("ScheduleExactAlarm")
    suspend fun reconcile(): Boolean = lock.withLock {
        refreshAccess(); val token = host.sessionToken()
        val jobs = host.access(token) { it.records().notifications().filter { job -> job.tableName == "anniversaries" } }
        val records = repository.records.first(); val clock = System.currentTimeMillis(); val zone = ZoneId.systemDefault()
        val desired = if (state.value.allowed) records.filter { it.enabled }.flatMap { record ->
            record.next(clock, zone, calendar)?.let { occurrence -> listOf("main", "lead").mapNotNull { kind ->
                val at = if (kind == "lead") occurrence - record.lead * 60_000L else occurrence
                val key = Uri.Builder().scheme("recoow-alarm").authority("anniversaries").appendPath(record.id).appendPath(occurrence.toString()).appendPath(kind).build().toString()
                if (at > clock && (kind == "main" || record.lead > 0) && ledger.getLong("sent:$key", Long.MIN_VALUE) != at) Alarm(key, at, anniversaryAlarmRevision(record)) else null
            } }.orEmpty()
        }.associateBy { it.key } else emptyMap()
        for (key in ledger.getStringSet("scheduled", emptySet()).orEmpty() - desired.keys) alarms.cancel(pending(key))
        manager.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { notice ->
            val id = notice.tag?.let(Uri::parse)?.pathSegments?.firstOrNull()
            if (records.none { it.id == id && it.enabled }) manager.cancel(notice.tag, notice.id)
        }
        val scheduled = mutableSetOf<String>(); var success = true
        for ((key, alarm) in desired) try {
            val p = pending(key, alarm.at, alarm.revision)
            if (state.value.exact) try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.at, p) }
                catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.at, p) }
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.at, p)
            scheduled += key
        } catch (_: Exception) { success = false }
        check(ledger.edit().putStringSet("scheduled", scheduled).commit())
        if (success && state.value.allowed) host.access(token) { db -> jobs.forEach { db.records().delivered(it.tableName, it.entityId, it.version) } }
        mutable.update { it.copy(failed = !success) }; success
    }
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun deliver(uri: Uri, scheduled: Long, revision: String?) = lock.withLock {
        val p = uri.pathSegments; val key = uri.toString()
        if (uri.scheme != "recoow-alarm" || uri.host != "anniversaries" || p.size != 3 || ledger.getLong("sent:$key", Long.MIN_VALUE) == scheduled) return@withLock
        val r = repository.records.first().firstOrNull { it.id == p[0] && it.enabled && anniversaryAlarmRevision(it) == revision } ?: return@withLock
        val occurrence = p[1].toLongOrNull() ?: return@withLock
        val expected = when (p[2]) { "main" -> occurrence; "lead" -> if (r.lead > 0) occurrence - r.lead * 60_000L else return@withLock; else -> return@withLock }
        val clock = System.currentTimeMillis(); val zone = ZoneId.systemDefault(); refreshAccess()
        if (!state.value.allowed || scheduled != expected || clock < scheduled || Instant.ofEpochMilli(clock).atZone(zone).toLocalDate() > Instant.ofEpochMilli(occurrence).atZone(zone).toLocalDate()) return@withLock
        val click = Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("recoow").authority("tool").appendPath("anniversaries").appendPath(r.id).build()).setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_reminder).setContentTitle(r.title)
            .setContentText(r.note).setStyle(Notification.BigTextStyle().bigText(r.note)).setAutoCancel(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, click, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (p[2] == "lead") notification.setSubText(context.getString(R.string.lead_notice, r.lead))
        check(ledger.edit().putLong("sent:$key", scheduled).commit()); manager.notify(key, 0, notification.build())
        val cutoff = clock - TimeUnit.DAYS.toMillis(400); val edit = ledger.edit()
        ledger.all.filter { it.key.startsWith("sent:") && (it.value as? Long ?: clock) < cutoff }.keys.forEach(edit::remove); edit.apply()
    }
    private fun pending(key: String, at: Long = 0, revision: String? = null) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderAlarmReceiver::class.java).setData(Uri.parse(key)).putExtra("scheduled", at).putExtra("revision", revision), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private data class Alarm(val key: String, val at: Long, val revision: String)
    companion object { const val CHANNEL = "anniversaries" }
}
@Module @InstallIn(SingletonComponent::class)
object AnniversaryNotificationModule {
    @Provides @Singleton fun calendar(): AnniversaryCalendar = IcuAnniversaryCalendar()
    @Provides fun notifications(runtime: AnniversaryNotificationRuntime): AnniversaryNotifications = runtime
}

// 同步确认只改变存储修订号，不影响已排定的提醒。
internal fun anniversaryAlarmRevision(record: Anniversary): String {
    val values = listOf(record.id, record.title, record.note, record.category.wire, record.occurredAt.toString(),
        record.calendar.name, record.yearly.toString(), record.minutes.toString(), record.lead.toString(), record.enabled.toString())
    val bytes = values.joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }.toByteArray()
    return java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
