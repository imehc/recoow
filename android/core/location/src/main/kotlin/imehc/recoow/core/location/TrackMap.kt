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
import org.maplibre.android.maps.MapLibreMapOptions
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
import org.maplibre.android.tile.TileOperation

/** 地图提供方自带已授权的样式与坐标系，代码中不写死任何地图服务器。 */
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
    backgroundArgb: Int = android.graphics.Color.LTGRAY,
    onFirstFrame: () -> Unit = {},
    requireBasemapTiles: Boolean = false,
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
    val firstFrame = rememberUpdatedState(onFirstFrame)
    // 替换样式使用独立视图，避免迟到的回调覆盖当前样式。
    androidx.compose.runtime.key(style, backgroundArgb) {
        val controller = remember { mutableStateOf<MapLibreMap?>(null) }
        val focused = remember { mutableStateOf<MapFocus?>(null) }
        val lifetime = remember { MapViewLifetime() }
        val view = remember(context) {
            var revealed = false
            var basemapTileReady = false
            MapLibre.getInstance(context)
            MapNetwork.install(context)
            MapView(context, MapLibreMapOptions().textureMode(true).foregroundLoadColor(backgroundArgb).logoEnabled(false)
                .localIdeographFontFamily("sans-serif").camera(CameraPosition.Builder().zoom(1.0).build())).apply {
                alpha = 0f
                onCreate(null)
                addOnDidFailLoadingMapListener { if (!lifetime.disposed && !isDestroyed) failure.value(it) }
                addOnDidFinishRenderingFrameListener(MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
                    if (!lifetime.disposed && !isDestroyed && controller.value != null && !revealed) {
                        revealed = true
                        post { if (!lifetime.disposed && !isDestroyed) { alpha = 1f; firstFrame.value() } }
                    }
                })
                addOnTileActionListener { operation, _, _, _, _, _, source ->
                    if (!lifetime.disposed && !isDestroyed && source != TRACK_SOURCE && source != MARKER_SOURCE) {
                        if (operation == TileOperation.EndParse) basemapTileReady = true
                        else if (operation == TileOperation.Error && !basemapTileReady) failure.value("Map tile request failed")
                    }
                }
                addOnDidFinishRenderingMapListener { fully ->
                    if (!lifetime.disposed && !isDestroyed && fully && controller.value != null && (!requireBasemapTiles || basemapTileReady)) rendered.value()
                }
                getMapAsync { map ->
                    if (lifetime.disposed || isDestroyed) return@getMapAsync
                    val builder = Style.Builder().let { if (style.json.startsWith("https://")) it.fromUri(style.json) else it.fromJson(style.json) }
                    map.setStyle(builder) { loadedStyle ->
                        if (lifetime.disposed || isDestroyed) return@setStyle
                        loadedStyle.addSource(GeoJsonSource(TRACK_SOURCE))
                        loadedStyle.addLayer(LineLayer(TRACK_LAYER, TRACK_SOURCE)
                            .withProperties(lineColor(Expression.get("color")), lineWidth(4f), lineCap("round"), lineJoin("round")))
                        loadedStyle.addSource(GeoJsonSource(MARKER_SOURCE))
                        loadedStyle.addLayer(CircleLayer(MARKER_LAYER, MARKER_SOURCE).withProperties(circleRadius(7f), circleColor(Expression.get("color")), circleStrokeWidth(2f), circleStrokeColor(android.graphics.Color.WHITE)))
                        controller.value = map
                        updateTrack(map, currentPoints.value, style.coordinates, color.value, currentLines.value, currentMarkers.value)
                        lifetime.overlay = MapOverlay(currentPoints.value, color.value, currentLines.value, currentMarkers.value)
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
                lifetime.disposed = true
                owner.lifecycle.removeObserver(observer)
                view.onPause()
                view.onStop()
                view.onDestroy()
            }
        }
        AndroidView(factory = { view }, modifier = modifier, update = {
            controller.value?.let { map ->
                val nextOverlay = MapOverlay(points, lineColorArgb, lines, markers)
                if (lifetime.overlay != nextOverlay) {
                    updateTrack(map, points, style.coordinates, lineColorArgb, lines, markers)
                    lifetime.overlay = nextOverlay
                }
                if (focus != null && focused.value != focus) { focusMap(map, focus, style.coordinates); focused.value = focus }
            }
        })
    }
}

private data class MapOverlay(val points: List<GeoCoordinate>, val color: Int, val lines: List<MapLine>?, val markers: List<MapMarker>)
private class MapViewLifetime { var disposed = false; var overlay: MapOverlay? = null }

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
