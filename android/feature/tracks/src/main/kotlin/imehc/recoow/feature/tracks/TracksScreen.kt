package imehc.recoow.feature.tracks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.location.GeoCoordinate
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.tracks.*
import imehc.recoow.core.location.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable fun TracksScreen(vm: TracksViewModel, onBack: () -> Unit, recordId: String? = null, onSource: (ToolRoute, String) -> Unit = { _, _ -> }) {
    val state by vm.state.collectAsStateWithLifecycle(); val detailId by vm.detailId.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle(); val draft by vm.draft.collectAsStateWithLifecycle()
    val recording by vm.recorder.state.collectAsStateWithLifecycle()
    var delivered by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) { if (recordId != null && recordId != delivered) { vm.show(recordId); delivered = recordId } }
    LaunchedEffect(recording.finishedId) { recording.finishedId?.let { vm.show(it); vm.recorder.dismissFinished() } }
    val back: () -> Unit = { if (!state.busy) { if (draft != null || detailId != null || history) vm.back() else onBack() } }
    BackHandler(draft != null || detailId != null || history || state.busy, back)
    when {
        draft != null -> {
            val d = requireNotNull(draft)
            RecordPage(stringResource(R.string.track_edit), back, busy = state.busy, action = stringResource(CommonR.string.record_save), actionEnabled = d.name.isNotBlank(), onAction = vm::save) {
                item { RecordField(stringResource(CommonR.string.record_name), d.name, { vm.update(d.copy(name = it)) }, !state.busy, tag = "track_name") }
                item { RecordField(stringResource(CommonR.string.record_note), d.note, { vm.update(d.copy(note = it)) }, !state.busy, multiline = true) }
            }
        }
        detailId != null -> key(detailId) { TrackDetails(vm, state, back, onSource) }
        history -> RecordPage(stringResource(R.string.track_history), back, tag = "track_history") {
            item { RecordLoadState(state.loading, state.failed, vm::retry) }
            if (!state.loading && !state.failed && state.tracks.isEmpty()) item { Text(stringResource(CommonR.string.record_empty)) }
            items(state.tracks, key = { it.id }) { track -> OutlinedCard(Modifier.fillMaxWidth().clickable { vm.show(track.id) }) { Column(Modifier.padding(16.dp)) {
                Text(track.name, style = MaterialTheme.typography.titleMedium); Text(trackDateTime(track.startedAt)); Text(statusName(track.status))
                Text(stringResource(R.string.track_summary, track.pointCount, track.distance / 1000, track.duration))
            } } }
        }
        else -> RecordingPage(vm, state, recording, back)
    }
    RecordProblem(state.error, vm::clearError)
}

@Composable private fun RecordingPage(vm: TracksViewModel, state: TracksState, recording: RecordingState, back: () -> Unit) {
    val context = LocalContext.current; val precision by vm.accuracy.collectAsStateWithLifecycle(); val detail by vm.activeDetail.collectAsStateWithLifecycle()
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val defaultName = stringResource(R.string.track_default_name, recordDateTime(System.currentTimeMillis()))
    val start = { vm.recorder.requestStart(defaultName, precision) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionDenied = result.values.none { it }; if (!permissionDenied) start()
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val current = recording.current
    RecordPage(stringResource(R.string.track_title), back, tag = "tool_locationTracker", busy = state.busy || recording.busy,
        action = stringResource(R.string.track_history), onAction = vm::showHistory) {
        item { Text(stringResource(R.string.track_background_help)) }
        item { RecordLoadState(state.loading, state.failed, vm::retry) }
        item {
            RecordChoice(stringResource(R.string.track_accuracy), current?.accuracy ?: precision, listOf(100, 10, 5).map { it to stringResource(R.string.track_accuracy_value, it) }, vm::accuracy, current == null && !recording.busy)
            Text(stringResource(R.string.track_accuracy_help), style = MaterialTheme.typography.bodySmall)
        }
        if (current != null) item {
            Text(current.name, style = MaterialTheme.typography.titleLarge); Text(statusName(current.status))
            Text(stringResource(R.string.track_summary, recording.pointCount,
                current.distance / 1000, recording.duration))
            Text(stringResource(R.string.track_max_speed, current.maxSpeed?.times(3.6)?.let { "%.1f".format(it) } ?: "—"))
            Text(recording.latest?.let { stringResource(R.string.track_coordinate, it.coordinate.latitude, it.coordinate.longitude) } ?: stringResource(R.string.track_waiting))
            TextButton(onClick = { vm.show(current.id) }) { Text(stringResource(R.string.track_details)) }
        }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!recording.running) Button(onClick = {
                if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) start()
                else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }, enabled = !recording.busy && !state.loading && !state.failed) { Text(stringResource(if (current == null) R.string.track_start else R.string.track_resume)) }
            else Button(onClick = vm.recorder::requestPause, enabled = !recording.busy) { Text(stringResource(R.string.track_pause)) }
            if (current != null) OutlinedButton(onClick = vm.recorder::requestFinish, enabled = !recording.busy) { Text(stringResource(R.string.track_finish)) }
        } }
        if (permissionDenied || recording.problem != null) item {
            Text(stringResource(if (permissionDenied) R.string.track_permission else when (recording.problem) {
                RecordingProblem.PERMISSION -> R.string.track_permission; RecordingProblem.PROVIDER -> R.string.track_provider
                RecordingProblem.CHANGED -> R.string.track_dataset_changed; RecordingProblem.INTERRUPTED -> R.string.track_interrupted; RecordingProblem.SAVE -> R.string.track_save_failed; else -> R.string.track_start_failed
            }))
            if (permissionDenied || recording.problem == RecordingProblem.PERMISSION) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text(stringResource(R.string.track_app_settings)) }
            if (recording.problem == RecordingProblem.PROVIDER) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text(stringResource(R.string.track_location_settings)) }
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) item {
            TextButton(onClick = { notification.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.track_enable_notification)) }
        }
        item { TrackMapPanel(detail.points, detail.segments, marker = recording.latest?.coordinate,
            focus = recording.latest?.let { MapFocus(listOf(it.coordinate), it.time.toString()) }) }
    }
}

@Composable private fun TrackDetails(vm: TracksViewModel, state: TracksState, back: () -> Unit, onSource: (ToolRoute, String) -> Unit) {
    val detail = state.detail; val track = detail.track; val points = detail.points; val segments = detail.segments
    var mode by rememberSaveable { mutableIntStateOf(0) }; var index by rememberSaveable { mutableIntStateOf(0) }
    var speed by rememberSaveable { mutableIntStateOf(1) }; var follow by rememberSaveable { mutableStateOf(false) }; var playing by remember { mutableStateOf(false) }
    var showPath by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }; var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }; var showPlaces by rememberSaveable { mutableStateOf(true) }; var showIssues by rememberSaveable { mutableStateOf(true) }
    var focus by remember { mutableStateOf<MapFocus?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current = points.getOrNull(index.coerceIn(0, (points.size - 1).coerceAtLeast(0)))
    val segment = segments.firstOrNull { it.id == selected }
    fun seek(value: Int) {
        index = value.coerceIn(0, (points.size - 1).coerceAtLeast(0))
        points.getOrNull(index)?.let { p -> selected = segments.firstOrNull { p.time in it.start..it.end }?.id
            if (follow) focus = MapFocus(listOf(p.coordinate), "play:$index") }
    }
    LaunchedEffect(playing, speed, points.size, follow) { if (playing) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (playing) { delay(700L / speed); if (index >= points.lastIndex) playing = false else seek(index + 1) }
    } }
    val places = remember(points) { TrackInsights.places(points) }; val issues = remember(points) { TrackInsights.quality(points) }
    val markers = buildList {
        if (mode == 1 && showPlaces) places.forEach { add(MapMarker(it.coordinate, Color(0xFF16A085).toArgb())) }
        if (mode == 1 && showIssues) issues.flatMap { it.occurrences }.forEach { add(MapMarker(it.point.coordinate, Color(0xFFE67E22).toArgb())) }
    }
    RecordPage(track?.name ?: stringResource(R.string.track_details), back, tag = "track_detail", busy = state.busy,
        action = if (track != null) stringResource(CommonR.string.record_edit) else null, onAction = { track?.let(vm::edit) }) {
        item { RecordLoadState(state.detailLoading, state.detailFailed, vm::observeDetail) }
        if (track == null && !state.detailLoading && !state.detailFailed) item { Text(stringResource(CommonR.string.record_missing)) }
        if (track != null) {
            item { Text(statusName(track.status)); Text(trackDateTime(track.startedAt)); track.note?.let { Text(it) }
                Text(stringResource(R.string.track_summary, points.size, track.distance / 1000, track.duration))
                Text(stringResource(R.string.track_max_speed, track.maxSpeed?.times(3.6)?.let { "%.1f".format(it) } ?: "—")) }
            item { TrackMapPanel(points, segments, selected, if (mode == 0) current?.coordinate else null, markers, focus, if (mode == 0 && showPath) points.take(index + 1) else emptyList())
                TextButton(onClick = { focus = MapFocus(points.map { it.coordinate }, "overview:${System.nanoTime()}"); selected = null; playing = false }) { Text(stringResource(R.string.track_overview)) } }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == 0, { mode = 0 }, label = { Text(stringResource(R.string.track_playback)) })
                FilterChip(mode == 1, { mode = 1; playing = false }, label = { Text(stringResource(R.string.track_insights)) })
            } }
            if (mode == 0) item {
                if (current == null) Text(stringResource(R.string.track_no_points)) else {
                    Text(trackDateTime(current.time)); Text(stringResource(R.string.track_point_number, index + 1, points.size))
                    Text(stringResource(R.string.track_coordinate, current.coordinate.latitude, current.coordinate.longitude))
                    Text(stringResource(R.string.track_point_metrics, current.speed?.times(3.6)?.let { "%.1f".format(it) } ?: "—", current.accuracy?.let { "%.0f".format(it) } ?: "—"))
                    Slider(index.toFloat().coerceIn(0f, points.lastIndex.coerceAtLeast(1).toFloat()), { playing = false; seek(it.roundToInt()) }, valueRange = 0f..points.lastIndex.coerceAtLeast(1).toFloat(), enabled = points.size > 1, modifier = Modifier.testTag("track_playback_progress").recordControlDescription(stringResource(R.string.track_playback), stringResource(R.string.track_point_number, index + 1, points.size)))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (index == points.lastIndex) seek(0); playing = !playing }, enabled = points.size > 1) { Text(stringResource(if (playing) R.string.track_pause else R.string.track_play)) }
                        listOf(1, 2, 4).forEach { value -> FilterChip(speed == value, { speed = value }, label = { Text("${value}×") }) }
                    }
                    RecordSwitch(stringResource(R.string.track_playback_path), showPath, { showPath = it })
                    RecordSwitch(stringResource(R.string.track_follow), follow, { follow = it; if (it) focus = MapFocus(listOf(current.coordinate), "follow:$index") })
                }
            }
            if (mode == 1) {
                item { Text(stringResource(R.string.track_motion_summary), style = MaterialTheme.typography.titleMedium)
                    segments.groupBy { it.motion }.forEach { (motion, values) -> Text(stringResource(R.string.track_motion_metrics, motionName(motion), values.sumOf { it.distance } / 1000, values.sumOf { it.duration })) }
                    RecordSwitch(stringResource(R.string.track_places), showPlaces, { showPlaces = it })
                    RecordSwitch(stringResource(R.string.track_quality), showIssues, { showIssues = it })
                }
                items(places) { place -> OutlinedCard(onClick = { playing = false; focus = MapFocus(listOf(place.coordinate), "place:${place.start}"); selected = segments.firstOrNull { place.start in it.start..it.end }?.id }) {
                    Column(Modifier.padding(12.dp)) { Text(stringResource(R.string.track_stay, (place.end - place.start) / 60_000)); Text(trackDateTime(place.start)) }
                } }
                items(issues) { issue -> Column { Text(stringResource(R.string.track_issue_count, qualityName(issue.kind), issue.count), style = MaterialTheme.typography.titleMedium)
                    issue.occurrences.forEach { occurrence -> TextButton(onClick = { playing = false; seek(points.indexOfFirst { it.id == occurrence.point.id }); focus = MapFocus(listOf(occurrence.point.coordinate), "issue:${issue.kind}:${occurrence.point.time}") }) {
                        Text("${trackDateTime(occurrence.point.time)} · ${"%.1f".format(occurrence.value)} ${if (issue.kind == TrackQuality.TIME_GAP) "s" else if (issue.kind == TrackQuality.WEAK_ACCURACY) "m" else "m/s"}")
                    } }
                } }
                if (places.isEmpty() && issues.isEmpty()) item { Text(stringResource(R.string.track_no_insights)) }
            }
            item { Text(stringResource(R.string.track_segments), style = MaterialTheme.typography.titleMedium) }
            items(segments, key = { it.id }) { s -> OutlinedCard(onClick = { selected = s.id; playing = false; focus = MapFocus(points.filter { it.time in s.start..s.end }.map { it.coordinate }, "segment:${s.id}") }) {
                Column(Modifier.padding(12.dp)) { Text(motionName(s.motion), style = MaterialTheme.typography.titleMedium)
                    Text("${trackDateTime(s.start)} — ${trackDateTime(s.end)}"); Text(stringResource(R.string.track_segment_metrics, s.distance / 1000, s.duration, stringResource(if (s.manual) R.string.track_manual else R.string.track_auto)))
                }
            } }
            if (segment != null && track.status == TrackStatus.FINISHED) item {
                RecordChoice(stringResource(R.string.track_segment_type), segment.motion, MotionType.entries.map { it to motionName(it) }, { vm.editSegment(detail, segment.id, SegmentEdit.Type(it)) }, !state.busy)
                val position = segments.indexOf(segment)
                FlowRow { TextButton(onClick = { editor = "split" }, enabled = !state.busy && points.count { it.time in segment.start..segment.end } > 2) { Text(stringResource(R.string.track_split)) }
                    TextButton(onClick = { editor = "bounds" }, enabled = !state.busy) { Text(stringResource(R.string.track_boundaries)) }
                    TextButton(onClick = { vm.editSegment(detail, segment.id, SegmentEdit.Merge(true)) }, enabled = !state.busy && segments.getOrNull(position - 1)?.end == segment.start) { Text(stringResource(R.string.track_merge_previous)) }
                    TextButton(onClick = { vm.editSegment(detail, segment.id, SegmentEdit.Merge(false)) }, enabled = !state.busy && segments.getOrNull(position + 1)?.start == segment.end) { Text(stringResource(R.string.track_merge_next)) }
                }
            }
            item { Text(stringResource(R.string.track_related), style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.track_related_help), style = MaterialTheme.typography.bodySmall) }
            items(detail.related, key = { "${it.route}:${it.id}" }) { related -> OutlinedCard(onClick = { onSource(related.route, related.id) }) { Column(Modifier.padding(12.dp)) {
                Text(related.title); Text(trackDateTime(related.time)); Text(stringResource(if (related.route == ToolRoute.BILLS) R.string.track_bill else R.string.track_diary))
            } } }
            if (track.status != TrackStatus.RECORDING) item { TextButton(onClick = { deleting = true }) { Text(stringResource(CommonR.string.record_delete)) } }
        }
    }
    if (editor != null && segment != null) key(segment.id, detail.revision) { SegmentEditor(segment, segments, points, editor == "split", { editor = null }) { edit -> editor = null; vm.editSegment(detail, segment.id, edit) } }
    if (deleting && track != null) RecordDeleteConfirmation(track.name, stringResource(R.string.track_delete), { deleting = false }) { deleting = false; vm.delete(detail) }
}

@Composable private fun SegmentEditor(segment: TrackSegment, segments: List<TrackSegment>, points: List<TrackPoint>, split: Boolean, dismiss: () -> Unit, save: (SegmentEdit) -> Unit) {
    val at = segments.indexOf(segment); val previous = segments.getOrNull(at - 1); val next = segments.getOrNull(at + 1)
    val choices = points.filter { it.time in (previous?.start ?: segment.start)..(next?.end ?: segment.end) }.map { it.time }.distinct()
    fun nearest(time: Long) = choices.indices.minByOrNull { kotlin.math.abs(choices[it] - time) } ?: 0
    var start by remember(segment.id) { mutableIntStateOf(nearest(segment.start)) }; var end by remember(segment.id) { mutableIntStateOf(nearest(segment.end)) }
    val inner = points.filter { it.time > segment.start && it.time < segment.end }.map { it.time }.distinct()
    var splitIndex by remember(segment.id) { mutableIntStateOf(inner.size / 2) }
    val valid = if (split) inner.isNotEmpty() else choices.getOrNull(start)?.let { s -> choices.getOrNull(end)?.let { e -> s < e && (previous == null || s > previous.start) && (next == null || e < next.end) } } == true
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(if (split) R.string.track_split else R.string.track_boundaries)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (split && inner.isNotEmpty()) { Text(trackDateTime(inner[splitIndex])); Slider(splitIndex.toFloat(), { splitIndex = it.roundToInt() }, valueRange = 0f..inner.lastIndex.coerceAtLeast(1).toFloat(), enabled = inner.size > 1, modifier = Modifier.recordControlDescription(stringResource(R.string.track_split), trackDateTime(inner[splitIndex]))) }
            else if (choices.isNotEmpty()) {
                Text(stringResource(R.string.track_start_boundary)); Text(trackDateTime(choices[start])); Slider(start.toFloat(), { start = it.roundToInt() }, valueRange = 0f..choices.lastIndex.coerceAtLeast(1).toFloat(), enabled = previous?.end == segment.start, modifier = Modifier.recordControlDescription(stringResource(R.string.track_start_boundary), trackDateTime(choices[start])))
                Text(stringResource(R.string.track_end_boundary)); Text(trackDateTime(choices[end])); Slider(end.toFloat(), { end = it.roundToInt() }, valueRange = 0f..choices.lastIndex.coerceAtLeast(1).toFloat(), enabled = next?.start == segment.end, modifier = Modifier.recordControlDescription(stringResource(R.string.track_end_boundary), trackDateTime(choices[end])))
            }
        }
    }, confirmButton = { TextButton(onClick = { save(if (split) SegmentEdit.Split(inner[splitIndex]) else SegmentEdit.Bounds(choices[start], choices[end])) }, enabled = valid) { Text(stringResource(CommonR.string.record_save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(CommonR.string.record_cancel)) } })
}

@Composable private fun TrackMapPanel(points: List<TrackPoint>, segments: List<TrackSegment>, selected: String? = null, marker: GeoCoordinate? = null,
    markers: List<MapMarker> = emptyList(), focus: MapFocus? = null, playback: List<TrackPoint> = emptyList()) {
    var offline by rememberSaveable { mutableStateOf(false) }; var ready by remember { mutableStateOf(false) }; var attempt by remember { mutableIntStateOf(0) }
    val primary = MaterialTheme.colorScheme.primary.toArgb(); val background = MaterialTheme.colorScheme.surface.toArgb()
    val style = if (offline) MapStyle("""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#${"%06X".format(background and 0xffffff)}"}}]}""") else MapStyle("https://tiles.openfreemap.org/styles/liberty")
    LaunchedEffect(offline, attempt) { ready = false; if (!offline) { delay(10_000); if (!ready) offline = true } }
    val lines = remember(points, segments, selected, primary) {
        if (segments.isEmpty()) {
            val groups = mutableListOf<MutableList<GeoCoordinate>>()
            points.forEachIndexed { index, point ->
                if (groups.isEmpty() || point.time - points[index - 1].time > 120_000) groups += mutableListOf<GeoCoordinate>()
                groups.last() += point.coordinate
            }
            groups.map { MapLine(it, primary) }
        }
        else segments.map { s -> MapLine(points.filter { it.time in s.start..s.end }.map { it.coordinate }, if (selected == s.id) primary else motionColor(s.motion)) }
    }
    val traversed = remember(playback, segments, primary) { segments.map { s -> MapLine(playback.filter { it.time in s.start..s.end }.map { it.coordinate }, primary) }.filter { it.points.size > 1 } }
    key(offline, attempt) { TrackMap(style, points.map { it.coordinate }, primary, Modifier.fillMaxWidth().height(270.dp).testTag("track_map"),
        onReady = { ready = true }, onFailure = { offline = true }, lines = lines + traversed,
        markers = markers + listOfNotNull(marker?.let { MapMarker(it, primary) }), focus = focus ?: MapFocus(points.map { it.coordinate }, "initial")) }
    if (offline) { Text(stringResource(R.string.track_map_offline)); TextButton(onClick = { offline = false; attempt++ }) { Text(stringResource(CommonR.string.record_retry)) } }
    Text("© OpenStreetMap contributors · OpenFreeMap", style = MaterialTheme.typography.labelSmall)
}

@Composable private fun statusName(status: TrackStatus) = stringResource(when (status) { TrackStatus.RECORDING -> R.string.track_recording; TrackStatus.PAUSED -> R.string.track_paused; TrackStatus.FINISHED -> R.string.track_finished })
@Composable private fun motionName(type: MotionType) = stringResource(when (type) {
    MotionType.UNKNOWN -> R.string.track_unknown; MotionType.STATIONARY -> R.string.track_stationary; MotionType.WALKING -> R.string.track_walking
    MotionType.RUNNING -> R.string.track_running; MotionType.CYCLING -> R.string.track_cycling; MotionType.TRANSIT -> R.string.track_transit; MotionType.DRIVING -> R.string.track_driving
})
@Composable private fun qualityName(kind: TrackQuality) = stringResource(when (kind) { TrackQuality.WEAK_ACCURACY -> R.string.track_weak_accuracy; TrackQuality.TIME_GAP -> R.string.track_time_gap; TrackQuality.SPEED_SPIKE -> R.string.track_speed_spike })
private fun motionColor(type: MotionType) = when (type) {
    MotionType.UNKNOWN, MotionType.STATIONARY -> 0xFF808080; MotionType.WALKING -> 0xFF27AE60; MotionType.RUNNING -> 0xFFE67E22
    MotionType.CYCLING -> 0xFF16A6B6; MotionType.TRANSIT -> 0xFF8E44AD; MotionType.DRIVING -> 0xFF2980B9
}.toInt()

@Composable private fun trackDateTime(timestamp: Long): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.MEDIUM)
        .withLocale(locale).format(java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()))
}
