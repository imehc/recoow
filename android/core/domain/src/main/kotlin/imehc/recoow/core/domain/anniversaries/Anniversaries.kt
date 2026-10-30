package imehc.recoow.core.domain.anniversaries

import imehc.recoow.core.domain.calendar.*
import imehc.recoow.core.domain.reminders.ReminderNotifications
import java.io.Serializable
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class AnniversaryCategory(val wire: String) { BIRTHDAY("birthday"), RELATIONSHIP("relationship"), FAMILY("family"), WORK_STUDY("workStudy"), PERSONAL_GOAL("personalGoal"), OTHER("other") }
data class Anniversary(val id: String, val revision: String, val title: String, val note: String?,
    val category: AnniversaryCategory, val occurredAt: Long, val calendar: DateCalendar, val yearly: Boolean,
    val minutes: Int, val lead: Int, val enabled: Boolean) {
    val schedule get() = AnniversarySchedule(Instant.ofEpochMilli(occurredAt), calendar, yearly, minutes)
    fun next(clock: Long, zone: ZoneId, engine: AnniversaryCalendar) = engine.nextOccurrence(schedule, Instant.ofEpochMilli(clock), zone)?.toEpochMilli()
    fun daysSince(clock: Long, zone: ZoneId) = ChronoUnit.DAYS.between(Instant.ofEpochMilli(occurredAt).atZone(zone).toLocalDate(), Instant.ofEpochMilli(clock).atZone(zone).toLocalDate())
    fun daysUntil(clock: Long, zone: ZoneId, engine: AnniversaryCalendar) = next(clock, zone, engine)?.let {
        ChronoUnit.DAYS.between(Instant.ofEpochMilli(clock).atZone(zone).toLocalDate(), Instant.ofEpochMilli(it).atZone(zone).toLocalDate())
    }
}
data class AnniversaryDraft(val id: String = UUID.randomUUID().toString(), val revision: String? = null, val title: String = "",
    val note: String = "", val category: AnniversaryCategory = AnniversaryCategory.OTHER, val occurredAt: Long = System.currentTimeMillis(),
    val calendar: DateCalendar = DateCalendar.GREGORIAN, val yearly: Boolean = true, val minutes: Int = 540, val lead: Int = 0, val enabled: Boolean = true) : Serializable {
    val valid get() = title.isNotBlank() && minutes in 0..1439 && lead in imehc.recoow.core.domain.reminders.leadMinutes
    companion object { fun from(a: Anniversary) = AnniversaryDraft(a.id, a.revision, a.title, a.note.orEmpty(), a.category, a.occurredAt, a.calendar, a.yearly, a.minutes, a.lead, a.enabled) }
}
interface AnniversariesRepository {
    val records: Flow<List<Anniversary>>
    suspend fun save(draft: AnniversaryDraft): String
    suspend fun delete(id: String, revision: String)
}
interface AnniversaryNotifications : ReminderNotifications
