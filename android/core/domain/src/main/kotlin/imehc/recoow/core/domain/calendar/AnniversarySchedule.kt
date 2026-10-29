package imehc.recoow.core.domain.calendar

import java.time.Instant
import java.time.ZoneId

enum class DateCalendar { GREGORIAN, CHINESE }

data class AnniversarySchedule(
    val occurredAt: Instant,
    val calendar: DateCalendar,
    val yearly: Boolean,
    val reminderTimeMinutes: Int,
) {
    init { require(reminderTimeMinutes in 0 until 24 * 60) }
}

data class CalendarDate(val era: Int, val year: Int, val month: Int, val day: Int, val leapMonth: Boolean)

interface AnniversaryCalendar {
    fun date(at: Instant, calendar: DateCalendar, zone: ZoneId): CalendarDate
    fun nextOccurrence(schedule: AnniversarySchedule, reference: Instant, zone: ZoneId): Instant?
}
