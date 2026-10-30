package imehc.recoow.feature.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.math.BigInteger
import java.text.NumberFormat
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
    onPeriod: (BillPeriod) -> Unit = {}, onRetry: () -> Unit = {}, onHistory: () -> Unit = {}, onTool: (ToolRoute, String?) -> Unit = { _, _ -> }) {
    var metric by rememberSaveable { mutableStateOf<BillMetric?>(null) }
    var amountSort by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val names = LocalResources.current.getStringArray(CommonR.array.record_tool_names)
    val labels = listOf(R.string.statistics_expense, R.string.statistics_income, R.string.statistics_bill_count, R.string.statistics_discount, R.string.statistics_stored_use)
    val money: (BigInteger) -> String = { cents -> NumberFormat.getCurrencyInstance(locale).apply { currency = java.util.Currency.getInstance("CNY") }.format(cents.toBigDecimal(2)) }
    val statistics = state.bills
    RecoowPage(stringResource(R.string.statistics_title)) {
        item { FilledTonalButton(onClick = onHistory, modifier = Modifier.fillMaxWidth().testTag("statistics_history")) { Text(stringResource(R.string.statistics_history)) } }
        if (state.loading.isNotEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.failed.isNotEmpty()) item { Column {
            Text(stringResource(R.string.statistics_partial, state.failed.map { key -> when (key) {
                "billDetails" -> names[ToolRoute.BILLS.ordinal]; "checkInProgress" -> names[ToolRoute.REMINDERS.ordinal]
                else -> ToolRoute.entries.firstOrNull { it.id == key }?.let { names[it.ordinal] }.orEmpty()
            } }.distinct().joinToString(" · ")))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.statistics_retry)) }
        } }
        item { Text(stringResource(R.string.statistics_overview), style = MaterialTheme.typography.titleLarge) }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.statistics_total, state.overview.total), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.statistics_today, state.overview.today))
            Text(stringResource(R.string.statistics_days, state.overview.activeDays))
            Text(stringResource(R.string.statistics_latest, state.overview.latest?.let { recordDateTime(it) } ?: stringResource(R.string.statistics_none)))
        } } }
        item { Text(stringResource(R.string.statistics_scope), style = MaterialTheme.typography.bodySmall) }
        item { Text(stringResource(R.string.statistics_recent), style = MaterialTheme.typography.titleLarge) }
        item { UsageBars(state) }
        item { Text(stringResource(R.string.statistics_tools), style = MaterialTheme.typography.titleLarge) }
        items(state.overview.tools, key = { "usage:${it.route.id}" }) { usage ->
            OutlinedCard(onClick = { onTool(usage.route, null) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(names[usage.route.ordinal], style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.statistics_usage, usage.count, usage.today))
                Text(stringResource(R.string.statistics_latest, usage.latest?.let { recordDateTime(it) } ?: stringResource(R.string.statistics_none)), style = MaterialTheme.typography.bodySmall)
            } }
        }
        item { Text(stringResource(R.string.statistics_checkins), style = MaterialTheme.typography.titleLarge) }
        if (state.progress.isEmpty()) item { Text(stringResource(R.string.statistics_no_progress)) }
        items(state.progress, key = { "progress:${it.id}" }) { progress ->
            OutlinedCard(onClick = { onTool(ToolRoute.REMINDERS, progress.id) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(progress.title, style = MaterialTheme.typography.titleMedium)
                progress.target?.let { Text(stringResource(R.string.statistics_progress, progress.completed, it, progress.remaining ?: 0)) }
                    ?: Text(stringResource(R.string.statistics_checkin_total, progress.completed))
                progress.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                Text(stringResource(R.string.statistics_streak, progress.currentStreak, progress.longestStreak))
            } }
        }
        item { Text(stringResource(R.string.statistics_bills), style = MaterialTheme.typography.titleLarge) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.statistics_week, R.string.statistics_month, R.string.statistics_year).forEachIndexed { i, label ->
                FilterChip(period.ordinal == i, { onPeriod(BillPeriod.entries[i]) }, label = { Text(stringResource(label)) })
            }
        } }
        if (statistics != null) {
            item { Text("${statistics.start} — ${statistics.endExclusive.minusDays(1)}", style = MaterialTheme.typography.bodySmall) }
            labels.forEachIndexed { index, label -> item {
                Card(onClick = { metric = BillMetric.entries[index] }, modifier = Modifier.fillMaxWidth().testTag("statistics_metric_$index")) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
                    val total = state.totals[BillMetric.entries[index]] ?: BigInteger.ZERO
                    Text(if (index == 2) total.toString() else money(total), style = MaterialTheme.typography.headlineSmall)
                } }
            } }
            item { Text(stringResource(R.string.statistics_bill_trend), style = MaterialTheme.typography.titleMedium) }
            item { BillBars(state.trend, period, money) }
            item { Text(stringResource(R.string.statistics_expense_share), style = MaterialTheme.typography.titleMedium) }
            item { Shares(state.expenseShares, false, money) }
            item { Text(stringResource(R.string.statistics_income_share), style = MaterialTheme.typography.titleMedium) }
            item { Shares(state.incomeShares, true, money) }
        }
    }
    metric?.let { selected -> if (statistics != null) {
        val details by produceState<List<BillRecord>?>(null, statistics, selected, amountSort) {
            value = null
            value = withContext(Dispatchers.Default) { statistics.rows(selected).sortedWith(
                if (amountSort) compareByDescending<BillRecord> { statistics.amount(it, selected) }.thenByDescending { it.occurredAt }.thenBy { it.id }
                else compareByDescending<BillRecord> { it.occurredAt }.thenBy { it.id }) }
        }
        ModalBottomSheet(onDismissRequest = { metric = null }) {
            LazyColumn(Modifier.fillMaxWidth().testTag("statistics_bill_details"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(labels[selected.ordinal]), style = MaterialTheme.typography.titleLarge) }
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!amountSort, { amountSort = false }, label = { Text(stringResource(R.string.statistics_sort_date)) })
                    FilterChip(amountSort, { amountSort = true }, label = { Text(stringResource(R.string.statistics_sort_amount)) })
                } }
                if (details == null) item { CircularProgressIndicator() }
                else if (details!!.isEmpty()) item { Text(stringResource(R.string.statistics_no_bills)) }
                items(details.orEmpty(), key = { it.id }) { bill ->
                    OutlinedCard(onClick = { metric = null; onTool(ToolRoute.BILLS, bill.id) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        Text(bill.title, style = MaterialTheme.typography.titleMedium)
                        Text(money(BigInteger.valueOf(statistics.amount(bill, selected))))
                        Text(recordDateTime(bill.occurredAt), style = MaterialTheme.typography.bodySmall)
                    } }
                }
            }
        }
    } }
}

@Composable private fun UsageBars(state: StatisticsState) {
    val locale = LocalConfiguration.current.locales[0]; val max = state.overview.recent.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.overview.recent.forEach { point ->
            val label = point.day.format(DateTimeFormatter.ofPattern("EEE", locale))
            val spoken = stringResource(R.string.statistics_usage_bar, point.day.toString(), point.count)
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(point.count.toString(), style = MaterialTheme.typography.labelSmall)
                Box(Modifier.height(100.dp).width(26.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().height((100f * point.count / max).dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
                }
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
@Composable private fun BillBars(points: List<BillTrend>, period: BillPeriod, money: (BigInteger) -> String) {
    val locale = LocalConfiguration.current.locales[0]
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val max = points.flatMap { listOf(it.expense, it.income) }.maxOrNull()?.max(BigInteger.ONE) ?: BigInteger.ONE
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.statistics_trend_legend), style = MaterialTheme.typography.bodySmall)
        if (points.all { it.count == 0 }) Text(stringResource(R.string.statistics_no_bills))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            points.forEach { point ->
                val spoken = stringResource(R.string.statistics_bill_bar, point.start.toString(), money(point.expense), money(point.income), point.count)
                Column(Modifier.widthIn(min = 48.dp).clickable { selected = point.start.toString() }.clearAndSetSemantics {
                    contentDescription = spoken
                    onClick { selected = point.start.toString(); true }
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.height(100.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(16.dp).height((100 * point.expense.toDouble() / max.toDouble()).toFloat().dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
                        Box(Modifier.width(16.dp).height((100 * point.income.toDouble() / max.toDouble()).toFloat().dp).background(MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraSmall))
                    }
                    Text(point.start.format(DateTimeFormatter.ofPattern(if (period == BillPeriod.YEAR) "MMM" else "d", locale)), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        points.firstOrNull { it.start.toString() == selected }?.let { point ->
            Text(stringResource(R.string.statistics_bill_bar, point.start.toString(), money(point.expense), money(point.income), point.count), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable private fun Shares(points: List<BillShare>, income: Boolean, money: (BigInteger) -> String) {
    val categories = if (income) incomeCategories else expenseCategories
    val names = LocalResources.current.getStringArray(if (income) R.array.statistics_income_categories else R.array.statistics_expense_categories)
    val total = points.fold(BigInteger.ZERO) { sum, p -> sum + p.amount }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (points.isEmpty()) Text(stringResource(R.string.statistics_no_bills))
        points.forEach { point ->
            val index = categories.indexOf(point.category).takeIf { it >= 0 } ?: categories.lastIndex
            val fraction = if (total.signum() == 0) 0f else (point.amount.toDouble() / total.toDouble()).toFloat()
            val text = stringResource(R.string.statistics_share_row, names[index], money(point.amount), NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0]).format(fraction), point.count)
            Column(Modifier.clearAndSetSemantics { contentDescription = text }) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
