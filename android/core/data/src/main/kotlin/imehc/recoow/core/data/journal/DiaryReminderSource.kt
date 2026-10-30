package imehc.recoow.core.data.journal

import android.content.res.Resources
import imehc.recoow.core.data.R
import imehc.recoow.core.domain.diary.DiaryLink
import imehc.recoow.core.domain.diary.DiarySource
import imehc.recoow.core.domain.reminders.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlinx.serialization.json.*

internal fun diaryReminderSource(reminder: Reminder, now: Long, zone: ZoneId, resources: Resources): DiaryLink {
    val locale = resources.configuration.locales[0]
    fun date(value: Long, withTime: Boolean = false): String = Instant.ofEpochMilli(value).atZone(zone).format(
        (if (withTime) DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT) else DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)).withLocale(locale))
    val schedule = when (reminder.kind) {
        ScheduleKind.SINGLE -> date(reminder.scheduledAt, true)
        ScheduleKind.RANGE -> listOfNotNull(date(reminder.scheduledAt), reminder.endAt?.let { date(it) }).joinToString(" – ")
        ScheduleKind.WEEKDAYS -> resources.getString(R.string.diary_source_weekdays)
        ScheduleKind.WEEKLY -> reminder.weekdays.sorted().joinToString(", ") { DayOfWeek.of((it + 5) % 7 + 1).getDisplayName(TextStyle.SHORT, locale) }
        ScheduleKind.CONTINUOUS -> resources.getString(R.string.diary_source_continuous, reminder.targetDays.coerceAtLeast(1))
        ScheduleKind.GOAL -> resources.getString(R.string.diary_source_goal)
    }
    val status = resources.getString(when (reminder.status(now, zone)) {
        ReminderStatus.READY -> R.string.diary_source_ready
        ReminderStatus.UPCOMING -> R.string.diary_source_upcoming
        ReminderStatus.CHECKED_IN -> R.string.diary_source_checked_in
        ReminderStatus.COMPLETED -> R.string.diary_source_completed
        ReminderStatus.PAUSED -> R.string.diary_source_paused
        ReminderStatus.DISABLED -> R.string.diary_source_disabled
        ReminderStatus.BROKEN -> R.string.diary_source_broken
        ReminderStatus.ENDED -> R.string.diary_source_ended
        ReminderStatus.ENDED_EARLY -> R.string.diary_source_ended_early
    })
    val total = reminder.totalDays(zone)
    val progress = if (total == null) resources.getString(R.string.diary_source_total, reminder.totalCheckIns)
        else resources.getString(R.string.diary_source_progress, minOf(reminder.totalCheckIns, total), total)
    val snapshot = buildJsonObject {
        put("id", reminder.id); put("title", reminder.title); reminder.note?.let { put("note", it) }
        put("scheduleKind", reminder.kind.wire); put("scheduleTitle", schedule); put("scheduledAt", reminder.scheduledAt)
        put("totalCheckInDays", reminder.totalCheckIns); total?.let { put("progressTotalDays", it) }; put("status", status)
    }
    return DiaryLink(DiarySource.REMINDER.wire, reminder.id, reminder.title, "$schedule · $progress · $status",
        DiarySource.REMINDER.icon, reminder.scheduledAt, snapshot.toString())
}
