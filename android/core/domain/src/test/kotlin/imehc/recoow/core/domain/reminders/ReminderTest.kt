package imehc.recoow.core.domain.reminders

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ReminderTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = LocalDate.of(2026, 10, 26) // Monday
    private fun at(day: LocalDate, minutes: Int = 600) = localTime(day, minutes, zone)
    private fun task(kind: ScheduleKind = ScheduleKind.GOAL) = Reminder("r", "v", "Read", at(start), kind = kind, timeMinutes = 600, targetDays = 3)
    private fun completion(day: LocalDate) = CheckIn(day, at(day), CheckInKind.CHECK_IN)

    @Test fun oneTimeReminderRetainsItsExactTimestamp() {
        val timestamp = at(start) + 37_123
        assertEquals(timestamp, task(ScheduleKind.SINGLE).copy(scheduledAt = timestamp).occurrence(start, zone))
    }
    @Test fun sixSchedulesUseLocalCalendarAndFoundationWeekdays() {
        assertNotNull(task(ScheduleKind.SINGLE).occurrence(start, zone))
        assertNull(task(ScheduleKind.SINGLE).occurrence(start.plusDays(1), zone))
        val range = task(ScheduleKind.RANGE).copy(endAt = at(start.plusDays(2)))
        assertEquals(3L, range.totalDays(zone)); assertNull(range.occurrence(start.plusDays(3), zone))
        assertNull(task(ScheduleKind.WEEKDAYS).occurrence(start.plusDays(5), zone))
        val weekly = task(ScheduleKind.WEEKLY).copy(weekdays = setOf(1, 2))
        assertNotNull(weekly.occurrence(start.plusDays(6), zone)); assertNull(weekly.occurrence(start.plusDays(1), zone))
        assertNull(task(ScheduleKind.CONTINUOUS).occurrence(start.plusDays(3), zone))
        assertNotNull(task().occurrence(start.plusYears(10), zone))
    }
    @Test fun todayCheckInDoesNotWaitForReminderTimeAndContinuousRequiresEarlierDays() {
        assertTrue(task().canCheckIn(at(start, 1), zone))
        val continuous = task(ScheduleKind.CONTINUOUS)
        assertFalse(continuous.canCheckIn(at(start.plusDays(1)), zone))
        assertEquals(start, continuous.firstMissed(at(start.plusDays(1)), zone))
        val madeUp = continuous.copy(completions = listOf(completion(start)))
        assertTrue(madeUp.canCheckIn(at(start.plusDays(1)), zone))
        assertEquals(1, madeUp.upcoming(at(start.plusDays(1), 1), zone).size)
    }
    @Test fun makeUpAcceptsAnyMissedPastDateWithinChallengeEvenWhenDisabled() {
        val r = task(ScheduleKind.CONTINUOUS).copy(enabled = false)
        val clock = at(start.plusDays(8))
        assertTrue(r.canMakeUp(start.plusDays(2), clock, zone))
        assertFalse(r.canMakeUp(start.plusDays(3), clock, zone))
        assertFalse(task().canMakeUp(start, at(start), zone))
        assertFalse(task().copy(pausedAt = at(start)).canMakeUp(start, clock, zone))
    }
    @Test fun importedDaysAffectProgressButDoNotCreateDailyCompletions() {
        val r = task(ScheduleKind.WEEKDAYS).copy(importedDays = 2, completions = listOf(completion(start)))
        assertTrue(r.completed(zone)); assertEquals(1, r.dates.size); assertEquals(3L, r.totalCheckIns)
        assertFalse(task().copy(importedDays = 9999).completed(zone))
        assertTrue(task().copy(endedAt = at(start.plusDays(1))).completed(zone))
    }
    @Test fun streaksCountImportedPrefixOnlyForDailyGoal() {
        val r = task().copy(importedDays = 10, completions = listOf(completion(start), completion(start.plusDays(1)), completion(start.plusDays(3))))
        assertEquals(12L, r.currentStreak(at(start.plusDays(2)), zone))
        assertEquals(1L, r.currentStreak(at(start.plusDays(3)), zone))
        assertEquals(12L, r.longestStreak(zone))
        assertEquals(2L, r.copy(kind = ScheduleKind.WEEKDAYS).longestStreak(zone))
    }
    @Test fun pauseTracksDurationWithoutMovingScheduleOrAllowingCheckIns() {
        val clock = at(start.plusDays(3))
        val r = task().copy(pausedAt = at(start.plusDays(1)), pausedMillis = 0)
        assertEquals(2L, r.elapsedDays(clock, zone)); assertEquals(start, r.start(zone))
        assertFalse(r.canCheckIn(clock, zone)); assertTrue(r.upcoming(clock, zone).isEmpty())
        assertEquals(2L, r.copy(pausedAt = null, pausedMillis = 2 * 86_400_000L).elapsedDays(clock, zone))
    }
    @Test fun dstGapUsesFirstValidTimeAndOverlapUsesFirstOccurrence() {
        val ny = ZoneId.of("America/New_York")
        assertEquals(Instant.parse("2026-03-08T07:00:00Z").toEpochMilli(), localTime(LocalDate.of(2026, 3, 8), 150, ny))
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), localTime(LocalDate.of(2026, 11, 1), 90, ny))
    }
    @Test fun validationRejectsEmptyWeeklyAndExcessImportButAllowsBrokenContinuous() {
        val d = ReminderDraft(title = "Read", scheduledAt = at(start), kind = ScheduleKind.CONTINUOUS, targetDays = "3")
        assertTrue(d.valid(null, at(start.plusDays(8)), zone))
        assertNull(d.copy(importedDays = "4").record(null, zone))
        assertNull(d.copy(kind = ScheduleKind.WEEKLY, weekdays = emptySet()).record(null, zone))
        assertFalse(d.copy(kind = ScheduleKind.SINGLE).valid(null, at(start.plusDays(1)), zone))
    }
}
