package imehc.recoow.core.domain.bills

import java.math.BigInteger
import java.time.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class BillStatisticsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-30T04:00:00Z").toEpochMilli()
    private fun bill(id: String, type: BillType = BillType.EXPENSE) = BillRecord(id, "v", id, BillAmounts(1000, 100, 900), type, "dining", PaymentMethod.CASH, now)
    @Test fun fiveMetricsUseSettlementRulesAndExcludeDeletedRows() {
        val rows = listOf(bill("expense"), bill("refund").copy(settlement = Settlement.REFUNDED), bill("income", BillType.INCOME).copy(amounts = BillAmounts(500, 0, 500)),
            bill("use", BillType.STORED_VALUE_USE).copy(amounts = BillAmounts(300, 0, 300)), bill("deleted").copy(deleted = true))
        val stats = BillStatistics(rows, BillPeriod.MONTH, now, zone, Locale.CHINA)
        assertEquals(listOf(900, 500, 4, 200, 300).map { BigInteger.valueOf(it.toLong()) }, BillMetric.entries.map(stats::total))
        assertEquals(listOf("expense", "refund"), stats.rows(BillMetric.DISCOUNT).map { it.id })
    }
    @Test fun localMonthIsHalfOpenAndLargeTotalsDoNotOverflow() {
        val start = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val huge = BillAmounts(Long.MAX_VALUE, 0, Long.MAX_VALUE)
        val stats = BillStatistics(listOf(bill("start").copy(occurredAt = start, amounts = huge), bill("end").copy(occurredAt = end),
            bill("late").copy(occurredAt = end - 1, amounts = huge)), BillPeriod.MONTH, now, zone, Locale.CHINA)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO, stats.total(BillMetric.EXPENSE))
        assertEquals(BigInteger.TWO, stats.total(BillMetric.COUNT))
    }
}
