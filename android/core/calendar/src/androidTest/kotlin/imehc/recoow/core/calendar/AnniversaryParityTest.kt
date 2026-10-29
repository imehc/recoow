package imehc.recoow.core.calendar

import imehc.recoow.core.domain.calendar.AnniversarySchedule
import imehc.recoow.core.domain.calendar.CalendarDate
import imehc.recoow.core.domain.calendar.DateCalendar
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class AnniversaryParityTest {
    @Test fun occurrenceAndCalendarFieldsMatchIOS() {
        val calendar = IcuAnniversaryCalendar()
        for (case in cases) {
            val occurredAt = Instant.parse(case.occurredAt)
            val zone = ZoneId.of(case.zone)
            assertEquals(case.name, case.date, calendar.date(occurredAt, case.kind, zone))
            val schedule = AnniversarySchedule(occurredAt, case.kind, case.yearly, case.minutes)
            assertEquals(case.name, case.expectedNext?.let(Instant::parse),
                calendar.nextOccurrence(schedule, Instant.parse(case.reference), zone))
        }
    }

    private data class Case(
        val name: String,
        val occurredAt: String,
        val reference: String,
        val expectedNext: String?,
        val date: CalendarDate,
        val kind: DateCalendar = DateCalendar.GREGORIAN,
        val zone: String = "Asia/Shanghai",
        val minutes: Int = 540,
        val yearly: Boolean = true,
    )

    // Expected values evaluated with AnniversaryRecord.swift / AnniversaryDateCalendar.swift
    // and Foundation at iOS commit e0a02fc141fe4b511b3fef11a9bd270ef45e8ef1.
    private val cases = listOf(
        Case("leap day constructed in non-leap year", "2024-02-29T01:00:00Z", "2025-01-01T00:00:00Z",
            "2025-03-01T01:00:00Z", CalendarDate(1, 2024, 2, 29, false)),
        Case("year added after leap day", "2024-02-29T01:00:00Z", "2024-03-01T00:00:00Z",
            "2025-02-28T01:00:00Z", CalendarDate(1, 2024, 2, 29, false)),
        Case("missing lunar leap month", "2023-03-22T01:00:00Z", "2024-01-01T00:00:00Z",
            "2024-04-09T01:00:00Z", CalendarDate(78, 40, 2, 1, true), DateCalendar.CHINESE),
        Case("year added after lunar leap month", "2023-04-19T01:00:00Z", "2023-04-20T00:00:00Z",
            "2024-05-07T01:00:00Z", CalendarDate(78, 40, 2, 29, true), DateCalendar.CHINESE),
        Case("missing lunar day 30", "2024-02-09T01:00:00Z", "2025-01-01T00:00:00Z",
            "2025-01-29T01:00:00Z", CalendarDate(78, 40, 12, 30, false), DateCalendar.CHINESE),
        Case("lunar new year", "2024-02-10T01:00:00Z", "2025-01-01T00:00:00Z",
            "2025-01-29T01:00:00Z", CalendarDate(78, 41, 1, 1, false), DateCalendar.CHINESE),
        Case("single reminder at DST gap", "2025-03-09T07:00:00Z", "2025-03-09T00:00:00Z",
            "2025-03-09T07:00:00Z", CalendarDate(1, 2025, 3, 9, false),
            zone = "America/New_York", minutes = 150, yearly = false),
        Case("annual reminder at DST gap", "2024-03-09T07:00:00Z", "2025-01-01T00:00:00Z",
            "2025-03-09T07:30:00Z", CalendarDate(1, 2024, 3, 9, false),
            zone = "America/New_York", minutes = 150),
        Case("first time in DST overlap", "2024-11-02T07:00:00Z", "2025-01-01T00:00:00Z",
            "2025-11-02T05:30:00Z", CalendarDate(1, 2024, 11, 2, false),
            zone = "America/New_York", minutes = 90),
        Case("past single reminder", "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z",
            null, CalendarDate(1, 2024, 1, 1, false), zone = "UTC", yearly = false),
        Case("future base takes precedence", "2027-05-01T00:00:00Z", "2026-01-01T00:00:00Z",
            "2027-05-01T00:00:00Z", CalendarDate(78, 44, 3, 25, false), DateCalendar.CHINESE, minutes = 480),
    )
}
