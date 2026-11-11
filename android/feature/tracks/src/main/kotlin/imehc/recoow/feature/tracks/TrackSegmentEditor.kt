package imehc.recoow.feature.tracks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.tracks.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TrackSegmentEditor(segment: TrackSegment, segments: List<TrackSegment>, points: List<TrackPoint>,
    mode: String, busy: Boolean, dismiss: () -> Unit, save: (SegmentEdit) -> Unit) {
    val at = segments.indexOf(segment); val previous = segments.getOrNull(at - 1); val next = segments.getOrNull(at + 1)
    val choices = points.filter { it.time in (previous?.start ?: segment.start)..(next?.end ?: segment.end) }.map { it.time }.distinct()
    fun nearest(time: Long) = choices.indices.minByOrNull { kotlin.math.abs(choices[it] - time) } ?: 0
    var start by remember(segment.id) { mutableIntStateOf(nearest(segment.start)) }
    var end by remember(segment.id) { mutableIntStateOf(nearest(segment.end)) }
    val inner = points.filter { it.time > segment.start && it.time < segment.end }.map { it.time }.distinct()
    var splitIndex by remember(segment.id) { mutableIntStateOf(inner.size / 2) }
    var motion by remember(segment.id) { mutableStateOf(segment.motion) }
    val valid = when (mode) {
        "type" -> true
        "split" -> inner.isNotEmpty()
        else -> choices.getOrNull(start)?.let { s -> choices.getOrNull(end)?.let { e ->
            s < e && (previous == null || s > previous.start) && (next == null || e < next.end)
        } } == true
    }
    val title = stringResource(when (mode) { "type" -> R.string.track_segment_type; "split" -> R.string.track_split; else -> R.string.track_boundaries })
    RecoowBottomSheet(onDismissRequest = dismiss, skipPartiallyExpanded = true) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            RecoowSheetHeader(title, Modifier.padding(horizontal = RecoowLayout.pagePadding), actions = {
                IconButton(onClick = {
                    save(when (mode) {
                        "type" -> SegmentEdit.Type(motion)
                        "split" -> SegmentEdit.Split(inner[splitIndex])
                        else -> SegmentEdit.Bounds(choices[start], choices[end])
                    })
                }, enabled = valid && !busy) { Icon(painterResource(CommonR.drawable.ic_check), stringResource(CommonR.string.record_save)) }
            })
            Column(Modifier.fillMaxWidth().padding(horizontal = RecoowLayout.pagePadding), verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap)) {
                when (mode) {
                    "type" -> RecoowGroup {
                        MotionType.entries.forEachIndexed { index, value ->
                            RecoowRow(motionName(value), onClick = { if (!busy) motion = value },
                                icon = { Icon(painterResource(trackMotionIcon(value)), null, tint = trackMotionTint(value)) },
                                trailing = { if (motion == value) Icon(painterResource(CommonR.drawable.ic_check), stringResource(R.string.track_selected), tint = MaterialTheme.colorScheme.primary) })
                            if (index < MotionType.entries.lastIndex) RecoowDivider(RecoowLayout.dividerInset)
                        }
                    }
                    "split" -> RecoowGroup {
                        if (inner.isEmpty()) RecoowEmptyState(stringResource(R.string.track_no_split_points)) else {
                            RecoowTextValueRow(stringResource(R.string.track_split_point), trackDateTime(inner[splitIndex]))
                            Slider(splitIndex.toFloat(), { splitIndex = it.roundToInt() }, valueRange = 0f..inner.lastIndex.coerceAtLeast(1).toFloat(),
                                enabled = inner.size > 1 && !busy,
                                modifier = Modifier.padding(horizontal = RecoowLayout.rowHorizontal).recordControlDescription(title, trackDateTime(inner[splitIndex])))
                        }
                    }
                    else -> RecoowGroup {
                        if (choices.isEmpty()) RecoowEmptyState(stringResource(R.string.track_no_points)) else {
                            RecoowTextValueRow(stringResource(R.string.track_start_boundary), trackDateTime(choices[start]))
                            Slider(start.toFloat(), { start = it.roundToInt() }, valueRange = 0f..choices.lastIndex.coerceAtLeast(1).toFloat(),
                                enabled = previous?.end == segment.start && !busy,
                                modifier = Modifier.padding(horizontal = RecoowLayout.rowHorizontal).recordControlDescription(stringResource(R.string.track_start_boundary), trackDateTime(choices[start])))
                            RecoowDivider()
                            RecoowTextValueRow(stringResource(R.string.track_end_boundary), trackDateTime(choices[end]))
                            Slider(end.toFloat(), { end = it.roundToInt() }, valueRange = 0f..choices.lastIndex.coerceAtLeast(1).toFloat(),
                                enabled = next?.start == segment.end && !busy,
                                modifier = Modifier.padding(horizontal = RecoowLayout.rowHorizontal).recordControlDescription(stringResource(R.string.track_end_boundary), trackDateTime(choices[end])))
                        }
                    }
                }
            }
        }
    }
}
