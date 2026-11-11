package imehc.recoow.feature.tracks

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.location.GeoCoordinate
import imehc.recoow.core.domain.tracks.*
import imehc.recoow.core.location.*
import kotlinx.coroutines.delay

@Composable internal fun TrackMapPanel(points: List<TrackPoint>, segments: List<TrackSegment>, selected: String? = null,
    marker: GeoCoordinate? = null, markers: List<MapMarker> = emptyList(), focus: MapFocus? = null, playback: List<TrackPoint> = emptyList(),
    onOverview: () -> Unit = {}) {
    var attempt by remember { mutableIntStateOf(0) }
    var loading by remember(attempt) { mutableStateOf(TrackMapLoading()) }
    var hasFrame by remember(attempt, loading.routeOnly) { mutableStateOf(false) }
    var manualFocus by remember { mutableStateOf<MapFocus?>(null) }
    LaunchedEffect(focus) { manualFocus = null }
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val background = MaterialTheme.colorScheme.surface.toArgb()
    val style = remember(loading.routeOnly, background) {
        if (loading.routeOnly) MapStyle("""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#${"%06X".format(background and 0xffffff)}"}}]}""")
        else MapStyle("https://tiles.openfreemap.org/styles/liberty")
    }
    LaunchedEffect(attempt) { delay(15_000); loading = loading.timedOut() }
    val route = remember(points) { points.map { it.coordinate } }
    val lines = remember(points, segments, selected, primary) {
        if (segments.isEmpty()) {
            val groups = mutableListOf<MutableList<GeoCoordinate>>()
            points.forEachIndexed { index, point ->
                if (groups.isEmpty() || point.time - points[index - 1].time > 120_000) groups += mutableListOf<GeoCoordinate>()
                groups.last() += point.coordinate
            }
            groups.map { MapLine(it, primary) }
        } else segments.map { segment -> MapLine(points.filter { it.time in segment.start..segment.end }.map { it.coordinate },
            if (selected == segment.id) primary else motionColor(segment.motion)) }
    }
    val traversed = remember(playback, segments, primary) {
        segments.map { segment -> MapLine(playback.filter { it.time in segment.start..segment.end }.map { it.coordinate }, primary) }.filter { it.points.size > 1 }
    }
    val allLines = remember(lines, traversed) { lines + traversed }
    val allMarkers = remember(markers, marker, primary) { markers + listOfNotNull(marker?.let { MapMarker(it, primary) }) }
    val initialFocus = remember(route) { MapFocus(route, "initial") }
    val shape = RoundedCornerShape(RecoowLayout.cardRadius)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(270.dp).clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)) {
            key(attempt) {
                TrackMap(style, route, primary, Modifier.fillMaxSize().testTag("track_map"), backgroundArgb = background,
                    requireBasemapTiles = !loading.routeOnly,
                    onReady = { if (!loading.routeOnly) loading = loading.styleLoaded() },
                    onFirstFrame = { hasFrame = true }, onRendered = { loading = loading.frameRendered() },
                    onFailure = { message ->
                        Log.w("RecoowTrackMap", "Basemap load failed: $message")
                        if (!loading.routeOnly) loading = loading.failed(message)
                    }, lines = allLines, markers = allMarkers, focus = manualFocus ?: focus ?: initialFocus)
            }
            if (!hasFrame && !loading.routeOnly) {
                Text(stringResource(R.string.track_map_loading),
                    Modifier.align(Alignment.Center).padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (loading.routeOnly) RecordBadge(stringResource(R.string.track_map_unavailable),
                Modifier.align(Alignment.TopStart).padding(8.dp), containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface)
            if (loading.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter))
            Column(Modifier.align(Alignment.BottomEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (route.isNotEmpty()) MapControl(CommonR.drawable.ic_tool_tracks, stringResource(R.string.track_overview)) {
                    onOverview()
                    manualFocus = MapFocus(route, "overview:${System.nanoTime()}")
                }
                marker?.let { position -> MapControl(CommonR.drawable.ic_location, stringResource(R.string.track_map_current_position)) {
                    manualFocus = MapFocus(listOf(position), "position:${System.nanoTime()}")
                } }
            }
        }
        if (loading.problem != null || loading.slow) RecoowNotice(stringResource(when (loading.problem) {
            TrackMapProblem.DNS -> R.string.track_map_dns_failed
            TrackMapProblem.NETWORK -> R.string.track_map_network_failed
            TrackMapProblem.SERVICE -> R.string.track_map_offline
            null -> R.string.track_map_slow
        }), actions = listOf(RecoowAction(stringResource(CommonR.string.record_retry)) { attempt++ }))
        Text("© OpenStreetMap contributors · OpenFreeMap", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun MapControl(icon: Int, label: String, action: () -> Unit) {
    Surface(shape = RoundedCornerShape(RecoowLayout.thumbnailRadius), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        IconButton(onClick = action, modifier = Modifier.size(RecoowLayout.controlSize)) {
            Icon(painterResource(icon), label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(RecoowLayout.iconSize))
        }
    }
}

private fun motionColor(type: MotionType) = when (type) {
    MotionType.UNKNOWN, MotionType.STATIONARY -> 0xFF808080; MotionType.WALKING -> 0xFF27AE60; MotionType.RUNNING -> 0xFFE67E22
    MotionType.CYCLING -> 0xFF16A6B6; MotionType.TRANSIT -> 0xFF8E44AD; MotionType.DRIVING -> 0xFF2980B9
}.toInt()
