package imehc.recoow.feature.tracks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.tracks.*
import imehc.recoow.core.location.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable internal fun TrackDetails(vm: TracksViewModel, state: TracksState, back: () -> Unit, onSource: (ToolRoute, String) -> Unit) {
    val detail = state.detail; val track = detail.track; val points = detail.points; val segments = detail.segments
    var mode by rememberSaveable { mutableIntStateOf(0) }; var index by rememberSaveable { mutableIntStateOf(0) }
    var speed by rememberSaveable { mutableIntStateOf(1) }; var follow by rememberSaveable { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }; var showPath by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }; var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }; var showPlaces by rememberSaveable { mutableStateOf(true) }
    var showIssues by rememberSaveable { mutableStateOf(true) }; var focus by remember { mutableStateOf<MapFocus?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current = points.getOrNull(index.coerceIn(0, (points.size - 1).coerceAtLeast(0)))
    val segment = segments.firstOrNull { it.id == selected }
    fun seek(value: Int) {
        index = value.coerceIn(0, (points.size - 1).coerceAtLeast(0))
        points.getOrNull(index)?.let { p ->
            selected = segments.firstOrNull { p.time in it.start..it.end }?.id
            if (follow) focus = MapFocus(listOf(p.coordinate), "play:$index")
        }
    }
    fun select(value: TrackSegment) {
        selected = value.id; playing = false
        focus = MapFocus(points.filter { it.time in value.start..value.end }.map { it.coordinate }, "segment:${value.id}")
    }
    LaunchedEffect(playing, speed, points.size, follow) {
        if (playing) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (playing) { delay(700L / speed); if (index >= points.lastIndex) playing = false else seek(index + 1) }
        }
    }
    val places = remember(points) { TrackInsights.places(points) }; val issues = remember(points) { TrackInsights.quality(points) }
    val markers = buildList {
        if (mode == 1 && showPlaces) places.forEach { add(MapMarker(it.coordinate, Color(0xFF16A085).toArgb())) }
        if (mode == 1 && showIssues) issues.flatMap { it.occurrences }.forEach { add(MapMarker(it.point.coordinate, Color(0xFFE67E22).toArgb())) }
    }
    RecordPage(track?.name ?: stringResource(R.string.track_details), back, tag = "track_detail", busy = state.busy, compact = true,
        more = if (track != null) buildList {
            add(RecoowAction(stringResource(CommonR.string.record_edit), enabled = !state.busy) { vm.edit(track) })
            if (track.status != TrackStatus.RECORDING) add(RecoowAction(stringResource(CommonR.string.record_delete), enabled = !state.busy) { deleting = true })
        } else emptyList()) {
        if (state.detailLoading || state.detailFailed) item(key = "loading") {
            if (state.detailLoading) RecordSkeleton() else RecordListFooter(failed = true, onRetry = vm::observeDetail)
        }
        if (track == null && !state.detailLoading && !state.detailFailed) item(key = "missing") {
            RecoowGroup { RecoowEmptyState(stringResource(CommonR.string.record_missing)) }
        }
        if (track != null) {
            item(key = "summary") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.track_record_summary))
                    RecoowGroup { TrackMetrics(track.distance, track.duration, points.size, track.maxSpeed) }
                }
            }
            item(key = "information") {
                RecoowGroup {
                    RecoowRow(stringResource(R.string.track_status), trailing = { TrackStatusBadge(track.status) })
                    RecoowDivider()
                    RecoowTextValueRow(stringResource(R.string.track_started_at), trackDateTime(track.startedAt))
                    track.endedAt?.let {
                        RecoowDivider()
                        RecoowTextValueRow(stringResource(R.string.track_ended_at), trackDateTime(it))
                    }
                    RecoowDivider()
                    RecoowTextValueRow(stringResource(R.string.track_accuracy), stringResource(R.string.track_accuracy_value, track.accuracy))
                    RecoowDivider()
                    RecoowTextValueRow(stringResource(R.string.track_average_speed), trackSpeed(track.averageSpeed))
                }
            }
            track.note?.takeIf { it.isNotBlank() }?.let { note -> item(key = "note") {
                TrackTextSection(stringResource(CommonR.string.record_note), note)
            } }
            item(key = "map") {
                Column {
                    TrackMapPanel(points, segments, selected, if (mode == 0) current?.coordinate else null, markers, focus,
                        if (mode == 0 && showPath) points.take(index + 1) else emptyList(), onOverview = { selected = null; playing = false })
                }
            }
            item(key = "modes") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode == 0, { mode = 0 }, label = { Text(stringResource(R.string.track_playback)) })
                    FilterChip(mode == 1, { mode = 1; playing = false }, label = { Text(stringResource(R.string.track_insights)) })
                }
            }
            if (mode == 0) item(key = "playback") {
                RecoowGroup {
                    if (current == null) RecoowEmptyState(stringResource(R.string.track_no_points)) else {
                        Column(Modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledIconButton(onClick = { if (index == points.lastIndex) seek(0); playing = !playing }, enabled = points.size > 1) {
                                    Icon(painterResource(if (playing) CommonR.drawable.ic_pause else CommonR.drawable.ic_play),
                                        stringResource(if (playing) R.string.track_pause else R.string.track_play))
                                }
                                Text(stringResource(R.string.track_point_number, index + 1, points.size), Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                listOf(1, 2, 4).forEach { value -> FilterChip(speed == value, { speed = value }, label = { Text("${value}×") }) }
                            }
                            Slider(index.toFloat().coerceIn(0f, points.lastIndex.coerceAtLeast(1).toFloat()), { playing = false; seek(it.roundToInt()) },
                                valueRange = 0f..points.lastIndex.coerceAtLeast(1).toFloat(), enabled = points.size > 1,
                                modifier = Modifier.testTag("track_playback_progress").recordControlDescription(stringResource(R.string.track_playback),
                                    stringResource(R.string.track_point_number, index + 1, points.size)))
                        }
                        RecoowDivider()
                        RecoowTextValueRow(stringResource(R.string.track_sample_time), trackDateTime(current.time))
                        RecoowDivider()
                        RecoowTextValueRow(stringResource(R.string.track_coordinate_label), stringResource(R.string.track_coordinate, current.coordinate.latitude, current.coordinate.longitude))
                        RecoowDivider()
                        RecoowTextValueRow(stringResource(R.string.track_point_speed), trackSpeed(current.speed))
                        RecoowDivider()
                        RecoowTextValueRow(stringResource(R.string.track_point_accuracy), current.accuracy?.let { stringResource(R.string.track_meters_value, it) } ?: "—")
                        RecoowDivider()
                        RecoowSwitchRow(stringResource(R.string.track_playback_path), showPath, { showPath = it })
                        RecoowDivider()
                        RecoowSwitchRow(stringResource(R.string.track_follow), follow, { follow = it; if (it) focus = MapFocus(listOf(current.coordinate), "follow:$index") })
                    }
                }
            }
            if (mode == 1) {
                item(key = "insights") {
                    Column {
                        RecoowSectionHeader(stringResource(R.string.track_motion_summary))
                        RecoowGroup {
                            val byMotion = segments.groupBy { it.motion }
                            byMotion.entries.forEachIndexed { at, (motion, values) ->
                                RecoowRow(motionName(motion), subtitle = trackDuration(values.sumOf { it.duration }),
                                    trailingText = trackDistance(values.sumOf { it.distance }),
                                    icon = { Icon(painterResource(trackMotionIcon(motion)), null, tint = trackMotionTint(motion)) })
                                if (at < byMotion.size - 1) RecoowDivider(RecoowLayout.dividerInset)
                            }
                            if (byMotion.isNotEmpty()) RecoowDivider()
                            RecoowSwitchRow(stringResource(R.string.track_places), showPlaces, { showPlaces = it })
                            RecoowDivider()
                            RecoowSwitchRow(stringResource(R.string.track_quality), showIssues, { showIssues = it })
                        }
                    }
                }
                if (places.isEmpty() && issues.isEmpty()) item(key = "no_insights") {
                    RecoowGroup { RecoowEmptyState(stringResource(R.string.track_no_insights)) }
                }
                if (places.isNotEmpty()) item(key = "places") {
                    Column {
                        RecoowSectionHeader(stringResource(R.string.track_stays))
                        RecoowGroup { places.forEachIndexed { at, place ->
                            RecordThumbnailRow(onClick = {
                                playing = false; focus = MapFocus(listOf(place.coordinate), "place:${place.start}")
                                selected = segments.firstOrNull { place.start in it.start..it.end }?.id
                            }, thumbnail = { TrackThumbnail(CommonR.drawable.ic_location, RecoowToolIcons.tint(ToolRoute.BILLS)) },
                                trailing = { TrackRowChevron() }) {
                                Text(stringResource(R.string.track_stay, (place.end - place.start) / 60_000), style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                                TrackMetadata(CommonR.drawable.ic_clock, trackDateTime(place.start))
                                Text(stringResource(R.string.track_coordinate, place.coordinate.latitude, place.coordinate.longitude),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (at < places.lastIndex) RecoowDivider(RecoowLayout.coverDividerInset)
                        } }
                    }
                }
                issues.forEach { issue -> item(key = "issue:${issue.kind}") {
                    Column {
                        RecoowSectionHeader(stringResource(R.string.track_issue_count, qualityName(issue.kind), issue.count))
                        RecoowGroup { issue.occurrences.forEachIndexed { at, occurrence ->
                            RecoowRow(trackDateTime(occurrence.point.time), trailingText = trackIssueValue(issue.kind, occurrence.value),
                                icon = { Icon(painterResource(CommonR.drawable.ic_location), null, tint = RecoowToolIcons.tint(ToolRoute.ANNIVERSARIES)) },
                                showChevron = true, onClick = {
                                    playing = false; seek(points.indexOfFirst { it.id == occurrence.point.id })
                                    focus = MapFocus(listOf(occurrence.point.coordinate), "issue:${issue.kind}:${occurrence.point.time}")
                                })
                            if (at < issue.occurrences.lastIndex) RecoowDivider(RecoowLayout.dividerInset)
                        } }
                    }
                } }
            }
            item(key = "segments") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.track_segments))
                    RecoowGroup {
                        if (segments.isEmpty()) RecoowEmptyState(stringResource(R.string.track_no_segments))
                        segments.forEachIndexed { at, value ->
                            val active = selected == value.id
                            val actions = if (track.status == TrackStatus.FINISHED) listOf(
                                RecoowAction(stringResource(R.string.track_segment_type), enabled = !state.busy) { select(value); editor = "type" },
                                RecoowAction(stringResource(R.string.track_split), enabled = !state.busy && points.count { it.time in value.start..value.end } > 2) { select(value); editor = "split" },
                                RecoowAction(stringResource(R.string.track_boundaries), enabled = !state.busy) { select(value); editor = "bounds" },
                                RecoowAction(stringResource(R.string.track_merge_previous), enabled = !state.busy && at > 0) { select(value); vm.editSegment(detail, value.id, SegmentEdit.Merge(true)) },
                                RecoowAction(stringResource(R.string.track_merge_next), enabled = !state.busy && at < segments.lastIndex) { select(value); vm.editSegment(detail, value.id, SegmentEdit.Merge(false)) },
                            ) else emptyList()
                            RecordThumbnailRow({ select(value) }, modifier = if (active) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f)) else Modifier,
                                thumbnail = { TrackThumbnail(trackMotionIcon(value.motion), trackMotionTint(value.motion)) }, trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(trackDistance(value.distance), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                        if (actions.isEmpty()) TrackRowChevron() else RecoowOverflowMenu(actions, tag = "track_segment_more:${value.id}")
                                    }
                                }) {
                                Text(motionName(value.motion), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                Text(trackTimeRange(value.start, value.end), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TrackMetadata(CommonR.drawable.ic_clock, trackDuration(value.duration))
                                    RecordBadge(stringResource(if (value.manual) R.string.track_manual else R.string.track_auto))
                                    if (active) RecordBadge(stringResource(R.string.track_selected), containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            if (at < segments.lastIndex) RecoowDivider(RecoowLayout.coverDividerInset)
                        }
                    }
                }
            }
            item(key = "related") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.track_related))
                    RecoowGroup {
                        if (detail.related.isEmpty()) RecoowEmptyState(stringResource(CommonR.string.record_empty), description = stringResource(R.string.track_related_help))
                        detail.related.forEachIndexed { at, related ->
                            RecordThumbnailRow({ onSource(related.route, related.id) },
                                thumbnail = { TrackThumbnail(RecoowToolIcons.drawable(related.route), RecoowToolIcons.tint(related.route)) }, trailing = { TrackRowChevron() }) {
                                Text(related.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                TrackMetadata(CommonR.drawable.ic_clock, trackDateTime(related.time))
                                RecordBadge(stringResource(if (related.route == ToolRoute.BILLS) R.string.track_bill else R.string.track_diary))
                            }
                            if (at < detail.related.lastIndex) RecoowDivider(RecoowLayout.coverDividerInset)
                        }
                    }
                }
            }
        }
    }
    if (editor != null && segment != null) key(segment.id, detail.revision) {
        TrackSegmentEditor(segment, segments, points, requireNotNull(editor), state.busy, { editor = null }) { edit ->
            editor = null; vm.editSegment(detail, segment.id, edit)
        }
    }
    if (deleting && track != null) RecordDeleteConfirmation(track.name, stringResource(R.string.track_delete), { deleting = false }) {
        deleting = false; vm.delete(detail)
    }
}

@Composable private fun TrackRowChevron() {
    Box(Modifier.size(RecoowLayout.controlSize), contentAlignment = Alignment.Center) { RecoowChevron() }
}

@Composable private fun trackIssueValue(kind: TrackQuality, value: Double): String = when (kind) {
    TrackQuality.TIME_GAP -> stringResource(R.string.track_seconds_decimal, value)
    TrackQuality.WEAK_ACCURACY -> stringResource(R.string.track_meters_value, value)
    TrackQuality.SPEED_SPIKE -> stringResource(R.string.track_meters_per_second, value)
}
