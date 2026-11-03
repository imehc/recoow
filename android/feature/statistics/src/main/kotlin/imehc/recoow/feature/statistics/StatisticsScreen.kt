package imehc.recoow.feature.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.statistics.CheckInProgress
import imehc.recoow.core.domain.statistics.UsageDay
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
    StatisticsScreen(state, period, vm::period, vm::retry, onHistory, onTool)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(state: StatisticsState = StatisticsState(), period: BillPeriod = BillPeriod.MONTH,
    onPeriod: (BillPeriod) -> Unit = {}, onRetry: () -> Unit = {}, onHistory: () -> Unit = {},
    onTool: (ToolRoute, String?) -> Unit = { _, _ -> }) {
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
            Text(
                stringResource(R.string.statistics_history),
                modifier = Modifier
                    .testTag("statistics_history")
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onHistory)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        contentSpacing = RecoowLayout.sectionGap,
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
                    RecoowMetricGrid(
                        listOf(
                            RecoowMetric(stringResource(R.string.statistics_records), numbers.format(state.overview.total)),
                            RecoowMetric(stringResource(R.string.statistics_today_label), numbers.format(state.overview.today)),
                            RecoowMetric(stringResource(R.string.statistics_days_label), numbers.format(state.overview.activeDays)),
                            RecoowMetric(stringResource(R.string.statistics_latest_label), latestLabel(state.overview.latest, locale), emphasis = false),
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
                    used.forEachIndexed { index, usage ->
                        RecoowToolRow(
                            route = usage.route,
                            title = names[usage.route.ordinal],
                            status = stringResource(R.string.statistics_usage, usage.count, usage.today),
                            onClick = { onTool(usage.route, null) },
                        )
                        if (index < used.lastIndex) RecoowDivider(RecoowLayout.dividerInset)
                    }
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
                        state.progress.forEachIndexed { index, progress ->
                            ProgressRow(progress) { onTool(ToolRoute.REMINDERS, progress.id) }
                            if (index < state.progress.lastIndex) RecoowDivider()
                        }
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
                        RecoowMetricGrid(
                            listOf(0, 1, 2, 3).map { index ->
                                val value = state.totals[BillMetric.entries[index]] ?: BigInteger.ZERO
                                RecoowMetric(
                                    label = stringResource(labels[index]),
                                    value = if (index == 2) numbers.format(value) else shortMoney(value),
                                    tag = "statistics_metric_$index",
                                    onClick = { metric = BillMetric.entries[index] },
                                )
                            },
                        )
                        RecoowDivider()
                        RecoowRow(
                            title = stringResource(labels[4]),
                            trailingText = shortMoney(state.totals[BillMetric.STORED_USE] ?: BigInteger.ZERO),
                            showChevron = true,
                            modifier = Modifier.testTag("statistics_metric_4"),
                            onClick = { metric = BillMetric.STORED_USE },
                        )
                    }
                }
            }
            item(key = "trend") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.statistics_bill_trend))
                    RecoowGroup { BillBars(state.trend, period, money) }
                }
            }
            item(key = "expense_share") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.statistics_expense_share))
                    RecoowGroup { Shares(state.expenseShares, false, money) }
                }
            }
            item(key = "income_share") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.statistics_income_share))
                    RecoowGroup { Shares(state.incomeShares, true, money) }
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
        ModalBottomSheet(
            onDismissRequest = { metric = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            LazyColumn(
                Modifier.fillMaxWidth().testTag("statistics_bill_details"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            ) {
                item { Text(stringResource(labels[selected.ordinal]), style = MaterialTheme.typography.titleLarge) }
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!amountSort, { amountSort = false }, label = { Text(stringResource(R.string.statistics_sort_date)) })
                        FilterChip(amountSort, { amountSort = true }, label = { Text(stringResource(R.string.statistics_sort_amount)) })
                    }
                }
                if (details == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else if (details!!.isEmpty()) item {
                    Text(stringResource(R.string.statistics_no_bills), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else itemsIndexed(details.orEmpty(), key = { _, bill -> bill.id }) { index, bill ->
                    if (index > 0) RecoowDivider()
                    RecoowRow(
                        title = bill.title,
                        subtitle = recordDateTime(bill.occurredAt),
                        trailingText = money(BigInteger.valueOf(statistics.amount(bill, selected))),
                        showChevron = true,
                        onClick = { metric = null; onTool(ToolRoute.BILLS, bill.id) },
                    )
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

/** 近七日：细柱 + 周标签，柱子高度按最大值归一。 */
@Composable
private fun UsageBars(points: List<UsageDay>) {
    val locale = LocalConfiguration.current.locales[0]
    val max = points.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        points.forEach { point ->
            val label = point.day.format(DateTimeFormatter.ofPattern("EEE", locale))
            val spoken = stringResource(R.string.statistics_usage_bar, point.day.toString(), point.count)
            Column(
                Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.height(84.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    if (point.count > 0) {
                        Box(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 4.dp)
                                .height((84f * point.count / max).dp)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 3.dp).height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PeriodSelector(period: BillPeriod, onPeriod: (BillPeriod) -> Unit) {
    val labels = listOf(R.string.statistics_week, R.string.statistics_month, R.string.statistics_year)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BillPeriod.entries.forEachIndexed { index, entry ->
            val selected = period == entry
            Surface(
                onClick = { onPeriod(entry) },
                modifier = Modifier.weight(1f).heightIn(min = 38.dp),
                shape = RoundedCornerShape(9.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(Modifier.fillMaxWidth().padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(labels[index]),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 打卡进度行：标题 + 完成度 + 一条细进度条。 */
@Composable
private fun ProgressRow(progress: CheckInProgress, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                progress.target?.let { "${progress.completed} / $it" } ?: progress.completed.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        progress.fraction?.let { fraction ->
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
            }
        }
        Text(
            progress.target?.let { stringResource(R.string.statistics_progress, progress.completed, it, progress.remaining ?: 0) }
                ?: stringResource(R.string.statistics_checkin_total, progress.completed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BillBars(points: List<BillTrend>, period: BillPeriod, money: (BigInteger) -> String) {
    val locale = LocalConfiguration.current.locales[0]
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val max = points.flatMap { listOf(it.expense, it.income) }.maxOrNull()?.max(BigInteger.ONE) ?: BigInteger.ONE
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (points.all { it.count == 0 }) {
            Text(stringResource(R.string.statistics_no_bills), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            points.forEach { point ->
                val spoken = stringResource(R.string.statistics_bill_bar, point.start.toString(), money(point.expense), money(point.income), point.count)
                Column(
                    Modifier.widthIn(min = 44.dp).clickable { selected = point.start.toString() }.clearAndSetSemantics {
                        contentDescription = spoken
                        onClick { selected = point.start.toString(); true }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(Modifier.height(88.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(
                            Modifier.width(15.dp).heightIn(min = if (point.expense.signum() > 0) 3.dp else 0.dp)
                                .height((88 * point.expense.toDouble() / max.toDouble()).toFloat().dp)
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Box(
                            Modifier.width(15.dp).heightIn(min = if (point.income.signum() > 0) 3.dp else 0.dp)
                                .height((88 * point.income.toDouble() / max.toDouble()).toFloat().dp)
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                .background(MaterialTheme.colorScheme.tertiary),
                        )
                    }
                    Text(
                        point.start.format(DateTimeFormatter.ofPattern(if (period == BillPeriod.YEAR) "MMM" else "d", locale)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.statistics_trend_legend),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        points.firstOrNull { it.start.toString() == selected }?.let { point ->
            Text(
                stringResource(R.string.statistics_bill_bar, point.start.toString(), money(point.expense), money(point.income), point.count),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Shares(points: List<BillShare>, income: Boolean, money: (BigInteger) -> String) {
    val categories = if (income) incomeCategories else expenseCategories
    val names = LocalResources.current.getStringArray(if (income) R.array.statistics_income_categories else R.array.statistics_expense_categories)
    val total = points.fold(BigInteger.ZERO) { sum, p -> sum + p.amount }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (points.isEmpty()) {
            Text(stringResource(R.string.statistics_no_bills), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        points.forEach { point ->
            val index = categories.indexOf(point.category).takeIf { it >= 0 } ?: categories.lastIndex
            val fraction = if (total.signum() == 0) 0f else (point.amount.toDouble() / total.toDouble()).toFloat()
            val percent = NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0]).format(fraction)
            Column(Modifier.clearAndSetSemantics {
                contentDescription = "${names[index]} $percent ${money(point.amount)}"
            }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        names[index],
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        "$percent · ${money(point.amount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outlineVariant)) {
                    Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}
