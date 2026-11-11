package imehc.recoow.feature.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.statistics.*
import java.math.BigInteger
import java.text.NumberFormat
import java.time.format.DateTimeFormatter

@Composable internal fun PeriodSelector(period: BillPeriod, onPeriod: (BillPeriod) -> Unit) {
    val labels = listOf(R.string.statistics_week, R.string.statistics_month, R.string.statistics_year)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal, vertical = 8.dp)) {
        BillPeriod.entries.forEachIndexed { index, value ->
            SegmentedButton(selected = period == value, onClick = { onPeriod(value) },
                shape = SegmentedButtonDefaults.itemShape(index, BillPeriod.entries.size), icon = {}, label = { Text(stringResource(labels[index])) })
        }
    }
}

@Composable internal fun UsageBars(points: List<UsageDay>) {
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    RecordCartesianChart(points.map {
        RecordChartPoint(it.day.format(DateTimeFormatter.ofPattern("EEE", locale)), listOf(it.count.toDouble()),
            stringResource(R.string.statistics_usage_bar, it.day.toString(), it.count))
    }, listOf(RecordChartSeries(stringResource(R.string.statistics_records), MaterialTheme.colorScheme.primary)),
        Modifier.padding(RecoowLayout.rowHorizontal).testTag("statistics_usage_chart"), integers = true,
        axisLabel = { numbers.format(it) })
}

@Composable internal fun BillBars(points: List<BillTrend>, period: BillPeriod, money: (BigInteger) -> String) {
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 } }
    if (points.all { it.count == 0 }) {
        RecoowEmptyState(stringResource(R.string.statistics_no_bills), icon = CommonR.drawable.ic_bill_chart)
        return
    }
    RecordCartesianChart(points.map {
        RecordChartPoint(it.start.format(DateTimeFormatter.ofPattern(if (period == BillPeriod.YEAR) "MMM" else "d", locale)),
            listOf(it.expense.toDouble(), it.income.toDouble()),
            stringResource(R.string.statistics_bill_bar, it.start.toString(), money(it.expense), money(it.income), it.count))
    }, listOf(RecordChartSeries(stringResource(R.string.statistics_expense), MaterialTheme.colorScheme.error),
        RecordChartSeries(stringResource(R.string.statistics_income), MaterialTheme.colorScheme.tertiary)),
        Modifier.padding(RecoowLayout.rowHorizontal).testTag("statistics_bill_chart"),
        style = if (period == BillPeriod.MONTH) RecordChartStyle.LINES else RecordChartStyle.BARS,
        integers = true, axisLabel = { "¥" + numbers.format(it / 100) })
}

@Composable internal fun BillShares(expenses: List<BillShare>, income: List<BillShare>, money: (BigInteger) -> String) {
    val divider = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().drawBehind {
        drawLine(divider, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 1.dp.toPx())
    }) {
        Shares(expenses, false, money, Modifier.weight(1f))
        Shares(income, true, money, Modifier.weight(1f))
    }
}

@Composable private fun Shares(points: List<BillShare>, income: Boolean, money: (BigInteger) -> String, modifier: Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val categories = if (income) incomeCategories else expenseCategories
    val names = LocalResources.current.getStringArray(if (income) R.array.statistics_income_categories else R.array.statistics_expense_categories)
    val total = points.fold(BigInteger.ZERO) { sum, p -> sum + p.amount }
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary,
        RecoowToolIcons.tint(ToolRoute.FOOD), RecoowToolIcons.tint(ToolRoute.DIARY),
        RecoowToolIcons.tint(ToolRoute.ITEMS), RecoowToolIcons.tint(ToolRoute.DECISION),
        RecoowToolIcons.tint(ToolRoute.ANNIVERSARIES), MaterialTheme.colorScheme.error,
        RecoowToolIcons.tint(ToolRoute.LOCATION),
        lerp(MaterialTheme.colorScheme.primary, RecoowToolIcons.tint(ToolRoute.DIARY), .5f),
        lerp(MaterialTheme.colorScheme.tertiary, RecoowToolIcons.tint(ToolRoute.ITEMS), .5f))
    val percent = NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0]).apply { maximumFractionDigits = 1 }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (income) R.string.statistics_income else R.string.statistics_expense),
            style = MaterialTheme.typography.labelLarge, color = if (income) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
        Text(money(total), Modifier.heightIn(min = 40.dp), style = MaterialTheme.typography.bodyLarge)
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RecordDonutChart(points.mapIndexed { index, point -> RecordChartSlice(
                if (total.signum() > 0) (point.amount.toDouble() / total.toDouble()).toFloat() else 0f, colors[index % colors.size]) },
                description = stringResource(if (income) R.string.statistics_income_share else R.string.statistics_expense_share),
                modifier = Modifier.testTag(if (income) "statistics_income_donut" else "statistics_expense_donut"), diameter = minOf(112.dp, maxWidth)) {
                Text(if (total.signum() == 0) stringResource(R.string.statistics_none) else stringResource(R.string.statistics_categories, points.size),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (total.signum() == 0) Text(stringResource(R.string.statistics_no_share), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val visible = if (expanded) points else points.take(3)
            visible.forEachIndexed { index, point ->
                val label = names[categories.indexOf(point.category).takeIf { it >= 0 } ?: categories.lastIndex]
                Column(Modifier.fillMaxWidth().testTag("statistics_${if (income) "income" else "expense"}_category_${point.category}").padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val color = colors[index % colors.size]
                        Canvas(Modifier.size(6.dp)) { drawCircle(color) }
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(percent.format(point.amount.toDouble() / total.toDouble()), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(money(point.amount), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (points.size > 3) TextButton(onClick = { expanded = !expanded },
                modifier = Modifier.testTag(if (income) "statistics_income_expand" else "statistics_expense_expand"), contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(if (expanded) R.string.statistics_collapse else R.string.statistics_more_categories, points.size - 3),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 工具入口与打卡任务共用平铺网格，不在分组内部再套卡片。 */
@Composable private fun <T> StatisticsGrid(items: List<T>, content: @Composable (T) -> Unit) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (largeText || maxWidth < 280.dp || items.size == 1) 1 else 2
        Column {
            items.chunked(columns).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) RecoowDivider(inset = 0.dp)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.forEachIndexed { index, item ->
                        if (index > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        Box(Modifier.weight(1f).fillMaxHeight()) { content(item) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable internal fun ToolUsageChart(overview: UsageOverview, onTool: (ToolRoute, String?) -> Unit) {
    val names = LocalResources.current.getStringArray(CommonR.array.record_tool_names)
    val percent = NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0])
    val used = overview.tools.filter { it.count > 0 }.sortedByDescending { it.count }
    val slices = used.map { RecordChartSlice((it.count.toFloat() / overview.total.coerceAtLeast(1)).coerceIn(0f, 1f), RecoowToolIcons.tint(it.route)) }
    RecordDistributionBar(slices, stringResource(R.string.statistics_tools),
        Modifier.padding(RecoowLayout.rowHorizontal).clip(RoundedCornerShape(5.dp)).testTag("statistics_tools_distribution"))
    StatisticsGrid(used) { usage ->
        val tint = RecoowToolIcons.tint(usage.route)
        val fraction = (usage.count.toFloat() / overview.total.coerceAtLeast(1)).coerceIn(0f, 1f)
        Column(Modifier.fillMaxWidth().clickable { onTool(usage.route, null) }
            .testTag("statistics_tool_${usage.route.id}").padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(RecoowToolIcons.drawable(usage.route)), null, tint = tint, modifier = Modifier.size(20.dp))
                Text(names[usage.route.ordinal], style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(stringResource(R.string.statistics_tool_share, usage.count, percent.format(fraction)), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.statistics_today, usage.today), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun CheckInGrid(progress: List<CheckInProgress>, onTool: (ToolRoute, String?) -> Unit) {
    StatisticsGrid(progress) { item ->
        Column(Modifier.fillMaxWidth().clickable { onTool(ToolRoute.REMINDERS, item.id) }
            .testTag("statistics_progress_${item.id}").padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title, Modifier.testTag("statistics_progress_title_${item.id}"), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.testTag("statistics_progress_value_${item.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val fraction = item.fraction
                if (fraction != null) Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxSize(), strokeWidth = 3.dp,
                        trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp)
                    Text(NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0]).format(fraction), style = MaterialTheme.typography.labelSmall)
                } else Icon(painterResource(CommonR.drawable.ic_tool_reminders), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp).padding(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.target?.let { stringResource(R.string.statistics_target_compact, item.completed, it) }
                        ?: stringResource(R.string.statistics_checkin_total, item.completed), style = MaterialTheme.typography.bodyMedium)
                    item.remaining?.let { Text(stringResource(R.string.statistics_remaining_compact, it), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text(stringResource(R.string.statistics_streak_compact, item.currentStreak, item.longestStreak), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
