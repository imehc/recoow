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
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** A provider supplies its own licensed style and coordinate system. No map server is hard-coded. */
data class MapStyle(val json: String, val coordinates: MapCoordinateSystem = MapCoordinateSystem.WGS84)

@Composable
fun TrackMap(
    style: MapStyle,
    points: List<GeoCoordinate>,
    lineColorArgb: Int,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    onRendered: () -> Unit = {},
    onFailure: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val ready = rememberUpdatedState(onReady)
    val rendered = rememberUpdatedState(onRendered)
    val failure = rememberUpdatedState(onFailure)
    val currentPoints = rememberUpdatedState(points)
    val color = rememberUpdatedState(lineColorArgb)
    // A style replacement gets its own view so late callbacks cannot overwrite the current style.
    androidx.compose.runtime.key(style) {
        val controller = remember { mutableStateOf<MapLibreMap?>(null) }
        val view = remember(context) {
            MapLibre.getInstance(context)
            MapView(context).apply {
                onCreate(null)
                addOnDidFailLoadingMapListener { failure.value(it) }
                addOnDidFinishRenderingMapListener { fully -> if (fully && controller.value != null) rendered.value() }
                getMapAsync { map ->
                    map.setStyle(Style.Builder().fromJson(style.json)) { loadedStyle ->
                        loadedStyle.addSource(GeoJsonSource(TRACK_SOURCE))
                        loadedStyle.addLayer(LineLayer(TRACK_LAYER, TRACK_SOURCE)
                            .withProperties(lineColor(color.value), lineWidth(4f)))
                        controller.value = map
                        updateTrack(map, currentPoints.value, style.coordinates, color.value)
                        currentPoints.value.firstOrNull()?.forDisplay(style.coordinates)?.let {
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
            controller.value?.let { map -> updateTrack(map, points, style.coordinates, lineColorArgb) }
        })
    }
}

private const val TRACK_SOURCE = "recoow-track"
private const val TRACK_LAYER = "recoow-track-line"

private fun updateTrack(map: MapLibreMap, points: List<GeoCoordinate>, system: MapCoordinateSystem, color: Int) {
    val geometry = points.map { it.forDisplay(system) }.map { Point.fromLngLat(it.longitude, it.latitude) }
    val features = if (geometry.size >= 2) arrayOf(Feature.fromGeometry(LineString.fromLngLats(geometry))) else emptyArray()
    map.style?.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    map.style?.getLayer(TRACK_LAYER)?.setProperties(lineColor(color))
}
