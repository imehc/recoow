package imehc.recoow.core.domain

import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.reminders.*
import imehc.recoow.core.domain.statistics.*
import java.math.BigInteger
import java.time.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class StatisticsRulesTest {
    private val zone = ZoneId.of("America/New_York")
    private fun time(day: String, hour: Int = 12) = LocalDate.parse(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    @Test fun totalsUseTaskDatesAndFoodDaysAcrossDstAndMidnight() {
        val now = time("2026-11-01", 23)
        val data = ToolRoute.entries.associateWith { route -> if (route == ToolRoute.FOOD)
            listOf(time("2026-10-31"), time("2026-11-01", 0), time("2026-11-01", 23)) else listOf(time("2026-10-31")) }
        val overview = usageOverview(data, now, zone)
        assertEquals(9, overview.total); assertEquals(1, overview.today); assertEquals(2, overview.activeDays)
        assertEquals(time("2026-11-01", 0), overview.latest)
        assertEquals(listOf(0, 0, 0, 0, 0, 8, 1), overview.recent.map { it.count })
        assertEquals(1, overview.tools.single { it.route == ToolRoute.REMINDERS }.count)
        assertEquals(2, overview.tools.single { it.route == ToolRoute.FOOD }.count)
        assertEquals(0, usageOverview(data, time("2026-11-02", 0), zone).today)
        assertNull(usageOverview(emptyMap(), now, zone).latest)
    }
    @Test fun fiveBillMetricsAndChartsShareSettlementRulesAndExactLargeAmounts() {
        val now = time("2026-11-01")
        fun bill(id: String, type: BillType, cents: Long, discount: Long = 0) = BillRecord(id, "", id,
            BillAmounts(cents + discount, discount, cents), type, if (type == BillType.INCOME) "salary" else "dining", PaymentMethod.CASH, now)
        val expense = bill("expense", BillType.EXPENSE, 1000, 200)
        val refunded = bill("void", BillType.EXPENSE, 300, 50).copy(settlement = Settlement.REFUNDED)
        val expired = bill("expired", BillType.EXPENSE, 500, 100).copy(category = "groupBuy", validUntil = now - 1)
        val all = listOf(expense, refunded, expired, bill("income", BillType.INCOME, 4000), bill("stored", BillType.STORED_VALUE_USE, 700),
            expense.copy(id = "outside", occurredAt = time("2026-12-01", 0)), expense.copy(id = "deleted", deleted = true))
        val stats = BillStatistics(all, BillPeriod.MONTH, now, zone, Locale.US)
        assertEquals(listOf(1000L, 4000L, 5L, 350L, 700L).map(BigInteger::valueOf), BillMetric.entries.map(stats::total))
        assertEquals(30, stats.trend().size); assertEquals(5, stats.trend().first().count)
        assertEquals(BigInteger.valueOf(1000), stats.trend().fold(BigInteger.ZERO) { sum, p -> sum + p.expense })
        assertEquals(stats.total(BillMetric.EXPENSE), stats.shares(BillType.EXPENSE).fold(BigInteger.ZERO) { sum, p -> sum + p.amount })
        assertEquals(3, stats.rows(BillMetric.EXPENSE).size); assertEquals(0L, stats.amount(refunded, BillMetric.EXPENSE))
        val huge = BillStatistics(listOf(bill("a", BillType.INCOME, Long.MAX_VALUE), bill("b", BillType.INCOME, Long.MAX_VALUE)), BillPeriod.YEAR, now, zone, Locale.US)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO, huge.total(BillMetric.INCOME))
        assertEquals(12, huge.trend().size)
        assertEquals(LocalDate.parse("2026-10-26"), BillStatistics(all, BillPeriod.WEEK, now, zone, Locale.UK).start)
    }
    @Test fun checkInProgressSharesStreaksHidesCompletedChallengesAndOrdersRemainingTasks() {
        val now = time("2026-11-01")
        val completion = CheckIn(LocalDate.parse("2026-10-31"), time("2026-10-31"), CheckInKind.CHECK_IN)
        val task = Reminder("task", "", "Task", time("2026-10-31"), kind = ScheduleKind.WEEKLY, targetDays = 3, completions = listOf(completion))
        val challenge = task.copy(id = "finished", kind = ScheduleKind.CONTINUOUS, targetDays = 1)
        val goal = task.copy(id = "goal", title = "Goal", kind = ScheduleKind.GOAL, importedDays = 4)
        val progress = checkInProgress(listOf(goal, challenge, task), now, zone)
        assertEquals(listOf("task", "goal"), progress.map { it.id }); assertEquals(2L, progress.first().remaining)
        assertEquals(5L, progress.last().currentStreak); assertEquals(5L, progress.last().longestStreak); assertNull(progress.last().target)
    }
}
