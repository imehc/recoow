package imehc.recoow.core.domain.reminders

import java.io.Serializable
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class ScheduleKind(val wire: String) { SINGLE("single"), RANGE("dateRange"), WEEKDAYS("weekdays"), WEEKLY("weekly"), CONTINUOUS("continuousDays"), GOAL("dailyGoal") }
enum class CheckInKind(val wire: String) { CHECK_IN("checkIn"), MAKE_UP("makeUp") }
enum class ReminderStatus { READY, UPCOMING, CHECKED_IN, COMPLETED, PAUSED, DISABLED, BROKEN, ENDED, ENDED_EARLY }
val reminderIcons = listOf("🔔", "📅", "📌", "💊", "🔑", "👛", "🎁", "📄", "🏠", "💼")
val leadMinutes = listOf(0, 5, 10, 30, 60, 1440)
data class CheckIn(val date: LocalDate, val completedAt: Long, val kind: CheckInKind, val note: String? = null)

data class Reminder(val id: String, val revision: String, val title: String, val scheduledAt: Long,
    val createdAt: Long = scheduledAt, val updatedAt: Long = scheduledAt, val note: String? = null, val icon: String = "🔔",
    val imageAssetId: String? = null, val hasImage: Boolean = false, val kind: ScheduleKind = ScheduleKind.GOAL,
    val endAt: Long? = null, val timeMinutes: Int = 0, val weekdays: Set<Int> = emptySet(), val targetDays: Long = 30,
    val importedDays: Long = 0, val enabled: Boolean = true, val lead: Int = 0, val completedAt: Long? = null,
    val endedAt: Long? = null, val endReason: String? = null, val pausedAt: Long? = null, val pauseReason: String? = null,
    val pausedMillis: Long = 0, val completions: List<CheckIn> = emptyList()) {
    val dates get() = completions.map { it.date }.toSet()
    val paused get() = kind == ScheduleKind.GOAL && pausedAt != null
    val ended get() = kind in setOf(ScheduleKind.CONTINUOUS, ScheduleKind.GOAL) && endedAt != null
    val totalCheckIns get() = Math.addExact(importedDays.coerceAtLeast(0), dates.size.toLong())
    fun start(zone: ZoneId) = Instant.ofEpochMilli(scheduledAt).atZone(zone).toLocalDate()
    fun totalDays(zone: ZoneId): Long? = when (kind) {
        ScheduleKind.SINGLE -> 1
        ScheduleKind.RANGE -> endAt?.let { (ChronoUnit.DAYS.between(start(zone), Instant.ofEpochMilli(it).atZone(zone).toLocalDate()) + 1).coerceAtLeast(1) }
        ScheduleKind.GOAL -> null
        else -> targetDays.coerceAtLeast(1)
    }
    fun completed(zone: ZoneId) = ended || kind != ScheduleKind.GOAL && (completedAt != null || totalDays(zone)?.let { totalCheckIns >= it } == true)
    fun canRestore(zone: ZoneId) = completed(zone) && kind != ScheduleKind.CONTINUOUS && !ended
    fun occurrence(day: LocalDate, zone: ZoneId): Long? {
        val start = start(zone)
        if (day < start) return null
        val end = when (kind) {
            ScheduleKind.SINGLE -> start
            ScheduleKind.CONTINUOUS -> start.plusDays(targetDays.coerceAtLeast(1) - 1)
            ScheduleKind.GOAL -> null
            else -> endAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        }
        if (end != null && day > end) return null
        val weekday = day.dayOfWeek.value % 7 + 1 // Foundation: Sunday = 1.
        if (kind == ScheduleKind.WEEKDAYS && weekday !in 2..6 || kind == ScheduleKind.WEEKLY && weekday !in weekdays) return null
        if (kind == ScheduleKind.SINGLE) return scheduledAt
        val minutes = if (timeMinutes > 0) timeMinutes else Instant.ofEpochMilli(scheduledAt).atZone(zone).let { it.hour * 60 + it.minute }
        return localTime(day, minutes, zone)
    }
    fun missedWindow(now: Long, zone: ZoneId): ClosedRange<LocalDate>? {
        if (kind !in setOf(ScheduleKind.CONTINUOUS, ScheduleKind.GOAL) || completed(zone) || paused) return null
        val first = start(zone)
        val yesterday = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1)
        val last = if (kind == ScheduleKind.CONTINUOUS) minOf(yesterday, first.plusDays(targetDays.coerceAtLeast(1) - 1)) else yesterday
        return (first..last).takeIf { last >= first }
    }
    fun canMakeUp(day: LocalDate, now: Long, zone: ZoneId) = missedWindow(now, zone)?.contains(day) == true && day !in dates
    fun firstMissed(now: Long, zone: ZoneId): LocalDate? {
        val range = missedWindow(now, zone) ?: return null
        val completed = dates
        var day = range.start
        // No calendar scan through decades: at most the number of existing completions + 1.
        while (day <= range.endInclusive && day in completed) day = day.plusDays(1)
        return day.takeIf { it <= range.endInclusive }
    }
    fun canCheckIn(now: Long, zone: ZoneId): Boolean {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return enabled && !completed(zone) && !paused && occurrence(day, zone) != null && day !in dates &&
            (kind != ScheduleKind.CONTINUOUS || firstMissed(now, zone) == null)
    }
    fun status(now: Long, zone: ZoneId): ReminderStatus = when {
        ended -> ReminderStatus.ENDED_EARLY
        paused -> ReminderStatus.PAUSED
        completed(zone) -> ReminderStatus.COMPLETED
        !enabled -> ReminderStatus.DISABLED
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate() in dates -> ReminderStatus.CHECKED_IN
        canCheckIn(now, zone) -> ReminderStatus.READY
        firstMissed(now, zone) != null -> ReminderStatus.BROKEN
        upcoming(now, zone, 1).isNotEmpty() -> ReminderStatus.UPCOMING
        else -> ReminderStatus.ENDED
    }
    fun upcoming(now: Long, zone: ZoneId, count: Int = 32): List<Long> {
        if (count <= 0 || completed(zone) || paused) return emptyList()
        if (kind == ScheduleKind.SINGLE) return listOf(scheduledAt).filter { it > now }
        val first = maxOf(start(zone), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        val completed = dates
        val result = mutableListOf<Long>()
        // The iOS search horizon is 370 days inclusive.
        for (offset in 0L..370L) {
            val day = first.plusDays(offset)
            if (kind == ScheduleKind.CONTINUOUS) {
                val preceding = ChronoUnit.DAYS.between(start(zone), day)
                if (completed.count { it >= start(zone) && it < day }.toLong() != preceding) continue
            }
            val occurrence = occurrence(day, zone) ?: continue
            if (occurrence > now && day !in completed) result += occurrence
            if (result.size == count) break
        }
        return result
    }
    fun currentStreak(now: Long, zone: ZoneId): Long {
        val completed = dates
        var day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (day !in completed) day = day.minusDays(1)
        val start = start(zone)
        var count = 0L
        while (day >= start && day in completed) { count++; day = day.minusDays(1) }
        return if (kind == ScheduleKind.GOAL && day < start) Math.addExact(count, importedDays.coerceAtLeast(0)) else count
    }
    fun longestStreak(zone: ZoneId): Long {
        val sorted = dates.sorted()
        if (sorted.isEmpty()) return importedDays.coerceAtLeast(0)
        var longest = 1L; var current = 1L
        for (index in 1 until sorted.size) { current = if (sorted[index - 1].plusDays(1) == sorted[index]) current + 1 else 1; longest = maxOf(longest, current) }
        if (kind == ScheduleKind.GOAL) {
            var day = start(zone); var prefix = 0L; val keys = dates
            while (day in keys) { prefix++; day = day.plusDays(1) }
            longest = maxOf(longest, Math.addExact(importedDays.coerceAtLeast(0), prefix))
        }
        return longest
    }
    fun elapsedDays(now: Long, zone: ZoneId): Long {
        val paused = Math.addExact(pausedMillis, if (paused) (now - requireNotNull(pausedAt)).coerceAtLeast(0) else 0)
        val through = Instant.ofEpochMilli(maxOf(scheduledAt, Math.subtractExact(now, paused))).atZone(zone).toLocalDate()
        return (ChronoUnit.DAYS.between(start(zone), through) + 1).coerceAtLeast(1)
    }
}

/** Calendar.nextTime resolves a DST gap at its end, rather than adding the gap to the requested minute. */
fun localTime(day: LocalDate, minutes: Int, zone: ZoneId): Long {
    val local = day.atTime((minutes / 60).coerceIn(0, 23), (minutes % 60).coerceIn(0, 59))
    val transition = zone.rules.getTransition(local)
    return (if (transition?.isGap == true) transition.dateTimeAfter.atZone(zone) else local.atZone(zone)).toInstant().toEpochMilli()
}

enum class ReminderImageEdit { KEEP, CLEAR, ASSET, FILE }
data class ReminderDraft(val id: String = UUID.randomUUID().toString(), val revision: String? = null, val title: String = "", val note: String = "",
    val icon: String = "🔔", val scheduledAt: Long = System.currentTimeMillis(), val kind: ScheduleKind = ScheduleKind.GOAL,
    val endAt: Long? = null, val timeMinutes: Int = Instant.ofEpochMilli(scheduledAt).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute },
    val weekdays: Set<Int> = setOf(2), val targetDays: String = "30", val importedDays: String = "0", val enabled: Boolean = true, val lead: Int = 0,
    val imageEdit: ReminderImageEdit = ReminderImageEdit.KEEP, val imageAssetId: String? = null, val imageFile: String? = null, val hasImage: Boolean = false, val imageSourceId: String? = null) : Serializable {
    fun record(previous: Reminder?, zone: ZoneId): Reminder? {
        val target = targetDays.toLongOrNull()?.takeIf { it in 1..9999 } ?: return null
        val imported = importedDays.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        if (title.isBlank() || timeMinutes !in 0..1439 || lead !in leadMinutes || kind == ScheduleKind.WEEKLY && (weekdays.isEmpty() || weekdays.any { it !in 1..7 })) return null
        val startDay = Instant.ofEpochMilli(scheduledAt).atZone(zone).toLocalDate()
        if (kind == ScheduleKind.RANGE && (endAt == null || Instant.ofEpochMilli(endAt).atZone(zone).toLocalDate() < startDay)) return null
        val result = (previous ?: Reminder(id, "", title, scheduledAt)).copy(title = title.trim(), note = note.trim().ifEmpty { null }, icon = icon.ifBlank { "🔔" },
            scheduledAt = if (kind == ScheduleKind.SINGLE) scheduledAt else localTime(startDay, timeMinutes, zone), kind = kind,
            endAt = endAt?.takeIf { kind == ScheduleKind.RANGE }?.let { localTime(Instant.ofEpochMilli(it).atZone(zone).toLocalDate(), timeMinutes, zone) },
            timeMinutes = timeMinutes, weekdays = if (kind == ScheduleKind.WEEKLY) weekdays else emptySet(), targetDays = target, importedDays = imported,
            enabled = enabled, lead = lead, endedAt = previous?.endedAt.takeIf { kind in setOf(ScheduleKind.CONTINUOUS, ScheduleKind.GOAL) },
            endReason = previous?.endReason.takeIf { kind in setOf(ScheduleKind.CONTINUOUS, ScheduleKind.GOAL) },
            pausedAt = previous?.pausedAt.takeIf { kind == ScheduleKind.GOAL }, pauseReason = previous?.pauseReason.takeIf { kind == ScheduleKind.GOAL })
        return result.takeIf { imported <= (it.totalDays(zone) ?: 9999) }
    }
    fun valid(previous: Reminder?, now: Long, zone: ZoneId): Boolean = record(previous, zone)?.let {
        !enabled || it.completed(zone) || kind == ScheduleKind.CONTINUOUS || it.canCheckIn(now, zone) || it.upcoming(now, zone, 1).isNotEmpty()
    } == true
    companion object {
        fun from(r: Reminder) = ReminderDraft(r.id, r.revision, r.title, r.note.orEmpty(), r.icon, r.scheduledAt, r.kind, r.endAt, r.timeMinutes,
            r.weekdays, r.targetDays.toString(), r.importedDays.toString(), r.enabled, r.lead, imageAssetId = r.imageAssetId, hasImage = r.hasImage)
    }
}
enum class ReminderAction { CHECK_IN, UNDO_TODAY, RESTORE, PAUSE, RESUME, END_EARLY }
class StaleReminderException : IllegalStateException("Reminder changed; reload before saving")
interface RemindersRepository {
    val reminders: Flow<List<Reminder>>
    suspend fun save(draft: ReminderDraft): String
    suspend fun act(id: String, action: ReminderAction, reason: String = "")
    suspend fun makeUp(id: String, dates: Set<LocalDate>, note: String)
    suspend fun deleteCompletion(id: String, date: LocalDate)
    suspend fun delete(id: String, revision: String)
}
