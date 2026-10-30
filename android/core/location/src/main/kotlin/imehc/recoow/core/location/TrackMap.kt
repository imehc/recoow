package imehc.recoow.core.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import imehc.recoow.core.domain.location.GeoCoordinate
import imehc.recoow.core.domain.location.MapCoordinateSystem
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** A provider supplies its own licensed style and coordinate system. No map server is hard-coded. */
data class MapStyle(val json: String, val coordinates: MapCoordinateSystem = MapCoordinateSystem.WGS84)
data class MapLine(val points: List<GeoCoordinate>, val color: Int)
data class MapMarker(val coordinate: GeoCoordinate, val color: Int)
data class MapFocus(val coordinates: List<GeoCoordinate>, val request: String)

@Composable
fun TrackMap(
    style: MapStyle,
    points: List<GeoCoordinate>,
    lineColorArgb: Int,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    onRendered: () -> Unit = {},
    onFailure: (String) -> Unit = {},
    lines: List<MapLine>? = null,
    markers: List<MapMarker> = emptyList(),
    focus: MapFocus? = null,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val ready = rememberUpdatedState(onReady)
    val rendered = rememberUpdatedState(onRendered)
    val failure = rememberUpdatedState(onFailure)
    val currentPoints = rememberUpdatedState(points)
    val color = rememberUpdatedState(lineColorArgb)
    val currentLines = rememberUpdatedState(lines)
    val currentMarkers = rememberUpdatedState(markers)
    val currentFocus = rememberUpdatedState(focus)
    // A style replacement gets its own view so late callbacks cannot overwrite the current style.
    androidx.compose.runtime.key(style) {
        val controller = remember { mutableStateOf<MapLibreMap?>(null) }
        val focused = remember { mutableStateOf<MapFocus?>(null) }
        val view = remember(context) {
            MapLibre.getInstance(context)
            MapView(context).apply {
                onCreate(null)
                addOnDidFailLoadingMapListener { failure.value(it) }
                addOnDidFinishRenderingMapListener { fully -> if (fully && controller.value != null) rendered.value() }
                getMapAsync { map ->
                    val builder = Style.Builder().let { if (style.json.startsWith("https://")) it.fromUri(style.json) else it.fromJson(style.json) }
                    map.setStyle(builder) { loadedStyle ->
                        loadedStyle.addSource(GeoJsonSource(TRACK_SOURCE))
                        loadedStyle.addLayer(LineLayer(TRACK_LAYER, TRACK_SOURCE)
                            .withProperties(lineColor(Expression.get("color")), lineWidth(4f)))
                        loadedStyle.addSource(GeoJsonSource(MARKER_SOURCE))
                        loadedStyle.addLayer(CircleLayer(MARKER_LAYER, MARKER_SOURCE).withProperties(circleRadius(7f), circleColor(Expression.get("color")), circleStrokeWidth(2f), circleStrokeColor(android.graphics.Color.WHITE)))
                        controller.value = map
                        updateTrack(map, currentPoints.value, style.coordinates, color.value, currentLines.value, currentMarkers.value)
                        if (currentFocus.value != null) { focusMap(map, currentFocus.value!!, style.coordinates); focused.value = currentFocus.value }
                        else currentPoints.value.firstOrNull()?.forDisplay(style.coordinates)?.let {
                            map.cameraPosition = CameraPosition.Builder().target(LatLng(it.latitude, it.longitude)).zoom(15.0).build()
                        }
                        ready.value()
                    }
                }
            }
        }
        DisposableEffect(view, owner) {
            val observer = object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = view.onStart()
                override fun onResume(owner: LifecycleOwner) = view.onResume()
                override fun onPause(owner: LifecycleOwner) = view.onPause()
                override fun onStop(owner: LifecycleOwner) = view.onStop()
            }
            owner.lifecycle.addObserver(observer)
            onDispose {
                owner.lifecycle.removeObserver(observer)
                view.onPause()
                view.onStop()
                view.onDestroy()
            }
        }
        AndroidView(factory = { view }, modifier = modifier, update = {
            controller.value?.let { map ->
                updateTrack(map, points, style.coordinates, lineColorArgb, lines, markers)
                if (focus != null && focused.value != focus) { focusMap(map, focus, style.coordinates); focused.value = focus }
            }
        })
    }
}

private const val TRACK_SOURCE = "recoow-track"
private const val TRACK_LAYER = "recoow-track-line"
private const val MARKER_SOURCE = "recoow-track-markers"
private const val MARKER_LAYER = "recoow-track-marker"

private fun updateTrack(map: MapLibreMap, points: List<GeoCoordinate>, system: MapCoordinateSystem, color: Int, lines: List<MapLine>?, markers: List<MapMarker>) {
    fun GeoCoordinate.point() = forDisplay(system).let { Point.fromLngLat(it.longitude, it.latitude) }
    fun colorText(value: Int) = "#%06X".format(value and 0xffffff)
    val features = (lines ?: listOf(MapLine(points, color))).filter { it.points.size > 1 }.map { line ->
        Feature.fromGeometry(LineString.fromLngLats(line.points.map { it.point() })).apply { addStringProperty("color", colorText(line.color)) }
    }.toTypedArray()
    map.style?.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    map.style?.getSourceAs<GeoJsonSource>(MARKER_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(markers.map { marker ->
        Feature.fromGeometry(marker.coordinate.point()).apply { addStringProperty("color", colorText(marker.color)) }
    }.toTypedArray()))
}

private fun focusMap(map: MapLibreMap, focus: MapFocus, system: MapCoordinateSystem) {
    val points = focus.coordinates.map { it.forDisplay(system) }.distinct().map { LatLng(it.latitude, it.longitude) }
    if (points.size == 1) map.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 16.0))
    else if (points.isNotEmpty()) map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 48))
}
