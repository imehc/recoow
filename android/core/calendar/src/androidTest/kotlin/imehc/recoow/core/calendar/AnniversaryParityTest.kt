package imehc.recoow.core.calendar

import imehc.recoow.core.domain.calendar.AnniversarySchedule
import imehc.recoow.core.domain.calendar.CalendarDate
import imehc.recoow.core.domain.calendar.DateCalendar
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class AnniversaryParityTest {
    @Test fun timezoneAndScheduleMatrixMatchesFoundation() {
        val calendar = IcuAnniversaryCalendar()
        for (case in timezoneCases) {
            val zone = ZoneId.of(case.zone)
            val occurred = Instant.parse(case.occurredAt)
            val reference = Instant.parse(case.reference)
            val name = "${case.zone} ${case.kind} ${case.occurredAt}"
            assertEquals(name, case.date, calendar.date(occurred, case.kind, zone))
            for ((minutes, next) in listOf(90 to case.at0130, 150 to case.at0230, 540 to case.at0900)) {
                for (yearly in listOf(false, true)) {
                    assertEquals("$name minutes=$minutes yearly=$yearly", if (yearly) Instant.parse(next) else null,
                        calendar.nextOccurrence(AnniversarySchedule(occurred, case.kind, yearly, minutes), reference, zone))
                }
            }
        }
    }

    @Test fun lunarInputRoundTripsLeapMonthAndRejectsMissingDates() {
        val zone = ZoneId.of("Asia/Shanghai")
        val leap = LunarDate(2023, 2, 1, true)
        assertEquals(java.time.LocalDate.of(2023, 3, 22), LunarDates.resolve(leap, zone))
        assertEquals(leap, LunarDates.from(Instant.parse("2023-03-22T01:00:00Z").toEpochMilli(), zone))
        org.junit.Assert.assertNull(LunarDates.resolve(leap.copy(year = 2024), zone))
        org.junit.Assert.assertNull(LunarDates.resolve(LunarDate(2025, 1, 31, false), zone))
        assertEquals(13, LunarDates.months(2023, zone).size)
        assertEquals(12, LunarDates.months(2024, zone).size)
        val calendar = IcuAnniversaryCalendar()
        assertEquals(1, calendar.yearsBetween(Instant.parse("2024-02-10T01:00:00Z"), Instant.parse("2025-01-29T01:00:00Z"), DateCalendar.CHINESE, zone))
        assertEquals(0, calendar.yearsBetween(Instant.parse("2024-02-10T01:00:00Z"), Instant.parse("2025-01-28T01:00:00Z"), DateCalendar.CHINESE, zone))
    }

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

    private data class TimezoneCase(
        val zone: String,
        val occurredAt: String,
        val reference: String,
        val kind: DateCalendar,
        val date: CalendarDate,
        val at0130: String,
        val at0230: String,
        val at0900: String,
    )

    // Fixed Foundation results from AnniversaryRecord.swift / AnniversaryDateCalendar.swift
    // at the same iOS baseline as the cases above. Six schedules share each input:
    // 01:30 / 02:30 / 09:00, once / yearly; all once-only dates are before reference.
    // 16 inputs cover 96 schedules without repeating the same fields in a JSON asset.
    private val timezoneCases = listOf(
        TimezoneCase("America/New_York", "2024-03-09T07:00:00Z", "2025-03-09T05:00:00Z",
            DateCalendar.GREGORIAN, CalendarDate(1, 2024, 3, 9, false),
            "2025-03-09T06:30:00Z", "2025-03-09T07:30:00Z", "2025-03-09T13:00:00Z"),
        TimezoneCase("America/New_York", "2024-03-09T07:00:00Z", "2025-03-09T05:00:00Z",
            DateCalendar.CHINESE, CalendarDate(78, 41, 1, 29, false),
            "2026-03-17T05:30:00Z", "2026-03-17T06:30:00Z", "2026-03-17T13:00:00Z"),
        TimezoneCase("America/New_York", "2024-11-02T07:00:00Z", "2025-11-02T04:00:00Z",
            DateCalendar.GREGORIAN, CalendarDate(1, 2024, 11, 2, false),
            "2025-11-02T05:30:00Z", "2025-11-02T07:30:00Z", "2025-11-02T14:00:00Z"),
        TimezoneCase("America/New_York", "2024-11-02T07:00:00Z", "2025-11-02T04:00:00Z",
            DateCalendar.CHINESE, CalendarDate(78, 41, 10, 2, false),
            "2025-11-21T06:30:00Z", "2025-11-21T07:30:00Z", "2025-11-21T14:00:00Z"),
        TimezoneCase("Europe/Berlin", "2024-03-30T10:00:00Z", "2025-03-29T23:00:00Z",
            DateCalendar.GREGORIAN, CalendarDate(1, 2024, 3, 30, false),
            "2025-03-30T00:30:00Z", "2025-03-30T01:30:00Z", "2025-03-30T07:00:00Z"),
        TimezoneCase("Europe/Berlin", "2024-03-30T10:00:00Z", "2025-03-29T23:00:00Z",
            DateCalendar.CHINESE, CalendarDate(78, 41, 2, 21, false),
            "2026-04-07T23:30:00Z", "2026-04-08T00:30:00Z", "2026-04-08T07:00:00Z"),
        TimezoneCase("Australia/Lord_Howe", "2024-10-05T22:00:00Z", "2025-10-04T13:30:00Z",
            DateCalendar.GREGORIAN, CalendarDate(1, 2024, 10, 6, false),
            "2025-10-05T14:30:00Z", "2025-10-05T15:30:00Z", "2025-10-05T22:00:00Z"),
        TimezoneCase("Australia/Lord_Howe", "2024-10-05T22:00:00Z", "2025-10-04T13:30:00Z",
            DateCalendar.CHINESE, CalendarDate(78, 41, 9, 4, false),
            "2025-10-23T14:30:00Z", "2025-10-23T15:30:00Z", "2025-10-23T22:00:00Z"),
        TimezoneCase("Asia/Kathmandu", "2024-02-29T00:00:00Z", "2025-01-01T00:00:00Z",
            DateCalendar.GREGORIAN, CalendarDate(1, 2024, 2, 29, false),
            "2025-02-28T19:45:00Z", "2025-02-28T20:45:00Z", "2025-03-01T03:15:00Z"),
        TimezoneCase("Asia/Kathmandu", "2024-02-29T00:00:00Z", "2025-01-01T00:00:00Z",
            DateCalendar.CHINESE, CalendarDate(78, 41, 1, 20, false),
            "2025-02-16T19:45:00Z", "2025-02-16T20:45:00Z", "2025-02-17T03:15:00Z"),
        TimezoneCase("Pacific/Apia", "2010-12-30T20:00:00Z", "2011-12-29T10:00:00Z",
            DateCalendar.GREGORIAN, CalendarDate(1, 2010, 12, 30, false),
            "2011-12-30T11:30:00Z", "2011-12-30T12:30:00Z", "2011-12-30T19:00:00Z"),
        TimezoneCase("Pacific/Apia", "2010-12-30T20:00:00Z", "2011-12-29T10:00:00Z",
            DateCalendar.CHINESE, CalendarDate(78, 27, 11, 25, false),
            "2013-01-05T11:30:00Z", "2013-01-05T12:30:00Z", "2013-01-05T19:00:00Z"),
        TimezoneCase("Pacific/Auckland", "2024-12-31T12:00:00Z", "2025-12-30T11:00:00Z",
            DateCalendar.GREGORIAN, CalendarDate(1, 2025, 1, 1, false),
            "2025-12-31T12:30:00Z", "2025-12-31T13:30:00Z", "2025-12-31T20:00:00Z"),
        TimezoneCase("Pacific/Auckland", "2024-12-31T12:00:00Z", "2025-12-30T11:00:00Z",
            DateCalendar.CHINESE, CalendarDate(78, 41, 12, 2, false),
            "2026-01-19T12:30:00Z", "2026-01-19T13:30:00Z", "2026-01-19T20:00:00Z"),
        TimezoneCase("Asia/Shanghai", "2023-03-22T01:00:00Z", "2026-01-01T00:00:00Z",
            DateCalendar.GREGORIAN, CalendarDate(1, 2023, 3, 22, false),
            "2026-03-21T17:30:00Z", "2026-03-21T18:30:00Z", "2026-03-22T01:00:00Z"),
        TimezoneCase("Asia/Shanghai", "2023-03-22T01:00:00Z", "2026-01-01T00:00:00Z",
            DateCalendar.CHINESE, CalendarDate(78, 40, 2, 1, true),
            "2026-04-16T17:30:00Z", "2026-04-16T18:30:00Z", "2026-04-17T01:00:00Z"),
    )
}
