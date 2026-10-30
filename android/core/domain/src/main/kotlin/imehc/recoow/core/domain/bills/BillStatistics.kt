package imehc.recoow.core.domain.bills

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

enum class BillPeriod { WEEK, MONTH, YEAR }
enum class BillMetric { EXPENSE, INCOME, COUNT, DISCOUNT, STORED_USE }
class BillStatistics(bills: List<BillRecord>, period: BillPeriod, private val now: Long, zone: ZoneId, locale: Locale) {
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
    // iOS discount metric reports the recorded discount; cash totals use settlement/expiry rules.
    fun total(metric: BillMetric): BigInteger = if (metric == BillMetric.COUNT) BigInteger.valueOf(records.size.toLong())
        else rows(metric).fold(BigInteger.ZERO) { sum, r -> sum + BigInteger.valueOf(if (metric == BillMetric.DISCOUNT) r.amounts.discount else r.countedAmount(now)) }
}
