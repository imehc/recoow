package imehc.recoow.core.domain.bills

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

enum class BillPeriod { WEEK, MONTH, YEAR }
enum class BillMetric { EXPENSE, INCOME, COUNT, DISCOUNT, STORED_USE }
data class BillTrend(val start: LocalDate, val expense: BigInteger, val income: BigInteger, val count: Int)
data class BillShare(val category: String, val amount: BigInteger, val count: Int)
class BillStatistics(bills: List<BillRecord>, private val period: BillPeriod, private val now: Long, private val zone: ZoneId, locale: Locale) {
    private val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val start: LocalDate = when (period) { BillPeriod.WEEK -> today.with(WeekFields.of(locale).dayOfWeek(), 1); BillPeriod.MONTH -> today.withDayOfMonth(1); BillPeriod.YEAR -> today.withDayOfYear(1) }
    val endExclusive: LocalDate = when (period) { BillPeriod.WEEK -> start.plusWeeks(1); BillPeriod.MONTH -> start.plusMonths(1); BillPeriod.YEAR -> start.plusYears(1) }
    private val records = bills.filter { !it.deleted && Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate().let { day -> day >= start && day < endExclusive } }
    fun rows(metric: BillMetric) = records.filter { when (metric) {
        BillMetric.EXPENSE -> it.type == BillType.EXPENSE
        BillMetric.INCOME -> it.type == BillType.INCOME
        BillMetric.COUNT -> true
        BillMetric.DISCOUNT -> it.type == BillType.EXPENSE && it.amounts.discount > 0
        BillMetric.STORED_USE -> it.type == BillType.STORED_VALUE_USE
    } }
    // iOS 的折扣指标上报实际记录的折扣；现金合计按结算与过期规则计算。
    fun total(metric: BillMetric): BigInteger = if (metric == BillMetric.COUNT) BigInteger.valueOf(records.size.toLong())
        else rows(metric).fold(BigInteger.ZERO) { sum, r -> sum + BigInteger.valueOf(if (metric == BillMetric.DISCOUNT) r.amounts.discount else r.countedAmount(now)) }
    fun amount(record: BillRecord, metric: BillMetric) = if (metric == BillMetric.DISCOUNT) record.amounts.discount else record.countedAmount(now)
    fun trend(): List<BillTrend> {
        val grouped = records.groupBy { val day = Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate(); if (period == BillPeriod.YEAR) day.withDayOfMonth(1) else day }
        return generateSequence(start) { if (period == BillPeriod.YEAR) it.plusMonths(1) else it.plusDays(1) }
            .takeWhile { it < endExclusive }.map { day -> val rows = grouped[day].orEmpty()
                BillTrend(day, sum(rows.filter { it.type == BillType.EXPENSE }), sum(rows.filter { it.type == BillType.INCOME }), rows.size)
            }.toList()
    }
    fun shares(type: BillType): List<BillShare> = records.filter { it.type == type }.groupBy { it.category }
        .map { (category, rows) -> BillShare(category, sum(rows), rows.size) }
        .sortedWith(compareByDescending<BillShare> { it.amount }.thenBy { it.category })
    private fun sum(rows: List<BillRecord>) = rows.fold(BigInteger.ZERO) { sum, r -> sum + BigInteger.valueOf(r.countedAmount(now)) }
}
