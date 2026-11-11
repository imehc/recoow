package imehc.recoow.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class RecordChartSeries(val label: String, val color: Color)
data class RecordChartPoint(val label: String, val values: List<Double>, val description: String)
data class RecordChartSlice(val fraction: Float, val color: Color)
enum class RecordChartStyle { BARS, LINES }

/** 四等分区间，刻度取可读值，而不是随数据浮动的任意最大值。 */
internal fun recordChartStep(maximum: Double, integers: Boolean): Double {
    if (!maximum.isFinite() || maximum <= 0.0) return 1.0
    val raw = maximum / 4.0
    val magnitude = 10.0.pow(floor(log10(raw)))
    val normalized = raw / magnitude
    val step = when {
        normalized <= 1 -> 1.0
        normalized <= 2 -> 2.0
        normalized <= 2.5 -> 2.5
        normalized <= 5 -> 5.0
        else -> 10.0
    } * magnitude
    return if (integers) ceil(step).coerceAtLeast(1.0) else step
}

/** 通用记录图表：坐标轴、与数据一致的图例，以及点按读数。 */
@Composable
fun RecordCartesianChart(points: List<RecordChartPoint>, series: List<RecordChartSeries>,
    modifier: Modifier = Modifier, style: RecordChartStyle = RecordChartStyle.BARS,
    integers: Boolean = false, axisLabel: (Double) -> String) {
    if (points.isEmpty() || series.isEmpty()) return
    var selected by remember(points) { mutableIntStateOf(points.indexOfLast { it.values.any { value -> value > 0 } }.coerceAtLeast(0)) }
    val maximum = points.flatMap { it.values }.filter { it.isFinite() }.maxOrNull() ?: 0.0
    val step = recordChartStep(maximum, integers)
    val top = step * 4
    val grid = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val previous = stringResource(R.string.record_chart_previous)
    val next = stringResource(R.string.record_chart_next)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RecordChartLegend(series)
        Row(Modifier.fillMaxWidth().height(148.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxHeight().padding(bottom = 20.dp).widthIn(min = 24.dp), verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End) {
                (4 downTo 0).forEach { tick -> Text(axisLabel(step * tick), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(Modifier.weight(1f)) {
                Canvas(Modifier.fillMaxWidth().weight(1f).padding(vertical = 7.dp).semantics {
                    contentDescription = points[selected].description
                    customActions = listOf(
                        CustomAccessibilityAction(previous) { selected = (selected - 1).coerceAtLeast(0); true },
                        CustomAccessibilityAction(next) { selected = (selected + 1).coerceAtMost(points.lastIndex); true },
                    )
                }.pointerInput(points) {
                    detectTapGestures { position -> selected = (position.x / size.width * points.size).toInt().coerceIn(points.indices) }
                }) {
                    val cell = size.width / points.size
                    val height = size.height
                    drawRect(highlight, Offset(cell * selected, 0f), Size(cell, height))
                    repeat(5) { tick ->
                        val y = height * tick / 4
                        drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    fun y(value: Double) = height * (1 - (value / top).coerceIn(0.0, 1.0)).toFloat()
                    series.forEachIndexed { seriesIndex, entry ->
                        if (style == RecordChartStyle.BARS) {
                            val width = (cell * 0.62f / series.size).coerceAtMost(24.dp.toPx())
                            points.forEachIndexed { index, point ->
                                val value = point.values.getOrElse(seriesIndex) { 0.0 }
                                if (value > 0 && value.isFinite()) {
                                    val barHeight = (height - y(value)).coerceAtLeast(2.dp.toPx())
                                    val x = cell * (index + 0.5f) + (seriesIndex - series.size / 2f) * width
                                    drawRoundRect(entry.color, Offset(x + 1.dp.toPx(), height - barHeight), Size((width - 2.dp.toPx()).coerceAtLeast(1f), barHeight),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                                }
                            }
                        } else {
                            val path = Path()
                            points.forEachIndexed { index, point ->
                                val x = cell * (index + 0.5f)
                                val y = y(point.values.getOrElse(seriesIndex) { 0.0 })
                                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, entry.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                            drawCircle(entry.color, 4.dp.toPx(), Offset(cell * (selected + 0.5f), y(points[selected].values.getOrElse(seriesIndex) { 0.0 })))
                        }
                    }
                }
                val ticks = if (points.size <= 7) points.indices.toList() else (0..6).map { (it * points.lastIndex / 6.0).toInt() }
                Layout(content = {
                    ticks.forEach { index -> Text(points[index].label, maxLines = 1,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { measurables, constraints ->
                    val labels = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth / ticks.size)) }
                    layout(constraints.maxWidth, labels.maxOfOrNull { it.height } ?: 0) {
                        labels.forEachIndexed { labelIndex, label ->
                            val center = constraints.maxWidth * (ticks[labelIndex] + .5f) / points.size
                            label.place((center - label.width / 2).toInt().coerceIn(0, constraints.maxWidth - label.width), 0)
                        }
                    }
                }
            }
        }
        // 读数说明按实际行数撑开，避免单行内容下方保留多行占位。
        Text(points[selected].description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RecordChartLegend(series: List<RecordChartSeries>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        series.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Canvas(Modifier.size(8.dp)) { drawCircle(entry.color) }
                Text(entry.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 环形数据图，配色与相邻的分类行保持一致。 */
@Composable
fun RecordDonutChart(slices: List<RecordChartSlice>, description: String, modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 132.dp,
    center: @Composable BoxScope.() -> Unit = {}) {
    val track = MaterialTheme.colorScheme.outlineVariant
    Box(modifier.size(diameter).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            var angle = -90f
            slices.forEach { slice ->
                val sweep = 360 * slice.fraction.coerceIn(0f, 1f)
                if (sweep > 0f) drawArc(slice.color, angle, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                angle += sweep
            }
        }
        center()
    }
}

/** 同一总量的连续占比条，颜色与对应的图例保持一致。 */
@Composable
fun RecordDistributionBar(slices: List<RecordChartSlice>, description: String, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = description }) {
        drawRect(track)
        var x = 0f
        slices.forEach { slice ->
            val width = size.width * slice.fraction.coerceIn(0f, 1f)
            drawRect(slice.color, Offset(x, 0f), Size(width, size.height))
            x += width
        }
    }
}
