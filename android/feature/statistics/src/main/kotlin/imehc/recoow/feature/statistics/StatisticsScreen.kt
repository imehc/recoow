package imehc.recoow.feature.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.media.MediaRepository
import imehc.recoow.core.media.RecordBillCoverRow
import java.math.BigInteger
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StatisticsRoute(vm: StatisticsViewModel, onHistory: () -> Unit, onTool: (ToolRoute, String?) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle(); val period by vm.period.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    RecordRefreshEffect { vm.refresh(locale = locale) }
    StatisticsScreen(state, period, vm::period, vm::retry, onHistory, onTool, vm.media)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(state: StatisticsState = StatisticsState(), period: BillPeriod = BillPeriod.MONTH,
    onPeriod: (BillPeriod) -> Unit = {}, onRetry: () -> Unit = {}, onHistory: () -> Unit = {},
    onTool: (ToolRoute, String?) -> Unit = { _, _ -> }, media: MediaRepository? = null) {
    var metric by rememberSaveable { mutableStateOf<BillMetric?>(null) }
    var amountSort by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val names = LocalResources.current.getStringArray(CommonR.array.record_tool_names)
    val labels = listOf(R.string.statistics_expense, R.string.statistics_income, R.string.statistics_bill_count,
        R.string.statistics_discount, R.string.statistics_stored_use)
    val currency: (BigInteger, Boolean) -> String = { cents, compact ->
        NumberFormat.getCurrencyInstance(locale).apply {
            currency = java.util.Currency.getInstance("CNY")
            maximumFractionDigits = if (!compact || cents.remainder(BigInteger.valueOf(100)).signum() != 0) 2 else 0
        }.format(cents.toBigDecimal(2))
    }
    val money: (BigInteger) -> String = { currency(it, false) }
    val shortMoney: (BigInteger) -> String = { currency(it, true) }
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val statistics = state.bills
    val recentTotal = state.overview.recent.sumOf { it.count }

    RecoowPage(
        title = stringResource(R.string.statistics_title),
        action = {
            IconButton(onClick = onHistory, modifier = Modifier.testTag("statistics_history")) {
                Icon(painterResource(CommonR.drawable.ic_clock), stringResource(R.string.statistics_history),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        contentSpacing = RecoowLayout.compactSectionGap,
    ) {
        if (state.loading.isNotEmpty()) item(key = "loading") {
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
        }
        if (state.failed.isNotEmpty()) item(key = "failed") {
            RecoowGroup {
                RecoowRow(
                    title = stringResource(R.string.statistics_partial, state.failed.map { key ->
                        when (key) {
                            "billDetails" -> names[ToolRoute.BILLS.ordinal]
                            "checkInProgress" -> names[ToolRoute.REMINDERS.ordinal]
                            else -> ToolRoute.entries.firstOrNull { it.id == key }?.let { names[it.ordinal] }.orEmpty()
                        }
                    }.distinct().joinToString(" · ")),
                    showChevron = true,
                    subtitle = stringResource(R.string.statistics_retry),
                    onClick = onRetry,
                )
            }
        }

        item(key = "overview") {
            Column {
                RecoowSectionHeader(stringResource(R.string.statistics_overview), trailing = stringResource(R.string.statistics_all_time))
                RecoowGroup {
                    RecoowMetricStrip(
                        listOf(
                            RecoowMetric(stringResource(R.string.statistics_records), numbers.format(state.overview.total), icon = CommonR.drawable.ic_tool_diary),
                            RecoowMetric(stringResource(R.string.statistics_today_label), numbers.format(state.overview.today), icon = CommonR.drawable.ic_check),
                            RecoowMetric(stringResource(R.string.statistics_days_label), numbers.format(state.overview.activeDays), icon = CommonR.drawable.ic_tool_anniversaries),
                            RecoowMetric(stringResource(R.string.statistics_latest_label), latestLabel(state.overview.latest, locale), icon = CommonR.drawable.ic_clock),
                        ),
                    )
                }
            }
        }

        item(key = "recent") {
            Column {
                RecoowSectionHeader(
                    stringResource(R.string.statistics_recent),
                    trailing = stringResource(R.string.statistics_recent_total, recentTotal),
                )
                RecoowGroup {
                    if (recentTotal == 0) {
                        Text(
                            stringResource(R.string.statistics_no_records),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        UsageBars(state.overview.recent)
                    }
                }
            }
        }

        val used = state.overview.tools.filter { it.count > 0 }
        if (used.isNotEmpty()) item(key = "tools") {
            Column {
                RecoowSectionHeader(stringResource(R.string.statistics_tools))
                RecoowGroup {
                    ToolUsageChart(state.overview, onTool)
                }
            }
        }

        item(key = "checkins") {
            Column {
                RecoowSectionHeader(stringResource(R.string.statistics_checkins))
                RecoowGroup {
                    if (state.progress.isEmpty()) {
                        Text(
                            stringResource(R.string.statistics_no_progress),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        CheckInGrid(state.progress, onTool)
                    }
                }
            }
        }

        if (statistics != null) {
            item(key = "bills") {
                Column {
                    RecoowSectionHeader(
                        stringResource(R.string.statistics_bills),
                        trailing = "${statistics.start} — ${statistics.endExclusive.minusDays(1)}",
                    )
                    RecoowGroup {
                        PeriodSelector(period, onPeriod)
                        RecoowDivider()
                        val metrics = BillMetric.entries.mapIndexed { index, entry ->
                            val value = state.totals[BillMetric.entries[index]] ?: BigInteger.ZERO
                            RecoowMetric(
                                label = stringResource(labels[index]),
                                value = if (index == 2) numbers.format(value) else shortMoney(value),
                                tag = "statistics_metric_$index",
                                onClick = { metric = entry },
                                icon = when (index) {
                                    0 -> CommonR.drawable.ic_bill_receipt
                                    1 -> CommonR.drawable.ic_tool_bills
                                    2 -> CommonR.drawable.ic_tool_diary
                                    3 -> CommonR.drawable.ic_bill_gift
                                    else -> CommonR.drawable.ic_tool_bills
                                },
                                tint = when (entry) {
                                    BillMetric.EXPENSE -> MaterialTheme.colorScheme.error
                                    BillMetric.INCOME -> MaterialTheme.colorScheme.tertiary
                                    BillMetric.STORED_USE -> recordBillTint(BillType.STORED_VALUE_USE)
                                    else -> null
                                },
                            )
                        }
                        RecoowMetricStrip(metrics.take(2))
                        RecoowDivider(inset = 0.dp)
                        RecoowMetricStrip(metrics.drop(2))
                    }
                }
            }
            item(key = "trend") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.statistics_bill_trend))
                    RecoowGroup { BillBars(state.trend, period, money) }
                }
            }
            item(key = "shares") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.statistics_category_shares))
                    RecoowGroup { BillShares(state.expenseShares, state.incomeShares, money) }
                }
            }
        }
        item(key = "scope") {
            Text(
                stringResource(R.string.statistics_scope),
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    metric?.let { selected -> if (statistics != null) {
        val details by produceState<List<BillRecord>?>(null, statistics, selected, amountSort) {
            value = null
            value = withContext(Dispatchers.Default) {
                statistics.rows(selected).sortedWith(
                    if (amountSort) compareByDescending<BillRecord> { statistics.amount(it, selected) }.thenByDescending { it.occurredAt }.thenBy { it.id }
                    else compareByDescending<BillRecord> { it.occurredAt }.thenBy { it.id },
                )
            }
        }
        RecoowBottomSheet(
            onDismissRequest = { metric = null },
            skipPartiallyExpanded = true,
        ) {
            LazyColumn(
                Modifier.fillMaxWidth().testTag("statistics_bill_details"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            ) {
                item {
                    RecoowSheetHeader(stringResource(labels[selected.ordinal]),
                        modifier = Modifier.testTag("statistics_details_header"),
                        value = if (selected == BillMetric.COUNT) numbers.format(state.totals[selected] ?: BigInteger.ZERO)
                            else money(state.totals[selected] ?: BigInteger.ZERO),
                        valueColor = when (selected) {
                            BillMetric.EXPENSE -> MaterialTheme.colorScheme.error
                            BillMetric.INCOME -> MaterialTheme.colorScheme.tertiary
                            BillMetric.STORED_USE -> recordBillTint(BillType.STORED_VALUE_USE)
                            else -> MaterialTheme.colorScheme.onSurface
                        })
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!amountSort, { amountSort = false }, label = { Text(stringResource(R.string.statistics_sort_date)) })
                        FilterChip(amountSort, { amountSort = true }, label = { Text(stringResource(R.string.statistics_sort_amount)) })
                    }
                }
                if (details == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else if (details!!.isEmpty()) item {
                    Text(stringResource(R.string.statistics_no_bills), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else itemsIndexed(details.orEmpty(), key = { _, bill -> bill.id }) { index, bill ->
                    if (index > 0) RecoowDivider()
                    RecordBillCoverRow(bill, media, money(BigInteger.valueOf(statistics.amount(bill, selected))),
                        onClick = { metric = null; onTool(ToolRoute.BILLS, bill.id) })
                }
            }
        }
    } }
}

/** 最近记录只给一个能一眼读懂的相对时间。 */
@Composable
private fun latestLabel(latest: Long?, locale: java.util.Locale): String {
    if (latest == null) return stringResource(R.string.statistics_none)
    val at = Instant.ofEpochMilli(latest).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val pattern = when {
        at.toLocalDate() == today -> "HH:mm"
        at.toLocalDate() == today.minusDays(1) -> "M/d"
        at.year == today.year -> "M/d"
        else -> "yyyy/M/d"
    }
    return at.format(DateTimeFormatter.ofPattern(pattern, locale))
}
