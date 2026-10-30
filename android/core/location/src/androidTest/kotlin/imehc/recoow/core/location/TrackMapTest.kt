package imehc.recoow.core.location

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import imehc.recoow.core.domain.location.GeoCoordinate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TrackMapTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rendersLocalMapWithoutGMSAndReleasesNativeView() {
        val style = MapStyle("""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#eeeeee"}}]}""")
        val visible = mutableStateOf(true)
        val ready = AtomicInteger()
        val frames = AtomicInteger()
        val failure = AtomicReference<String?>()
        val focus = mutableStateOf(MapFocus(listOf(GeoCoordinate(39.907, 116.397)), "start"))
        val route = (0..2000).map { GeoCoordinate(39.907 + it * 0.000001, 116.397 + it * 0.000001) }
        compose.setContent {
            if (visible.value) TrackMap(style, route,
                0xff6650a4.toInt(), Modifier.fillMaxSize(), onReady = { ready.incrementAndGet() },
                onRendered = { frames.incrementAndGet() }, onFailure = { failure.set(it) },
                lines = listOf(MapLine(route.take(1001), 0xff27ae60.toInt()), MapLine(route.drop(1000), 0xff2980b9.toInt())),
                markers = listOf(MapMarker(route.first(), 0xff6650a4.toInt()), MapMarker(route.last(), 0xffe67e22.toInt())), focus = focus.value)
        }
        compose.waitUntil(20_000) { ready.get() == 1 && frames.get() > 0 || failure.get() != null }
        assertNull(failure.get())
        compose.runOnIdle { focus.value = MapFocus(listOf(route.last()), "end") }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        frames.set(0)
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(20_000) { ready.get() == 2 && frames.get() > 0 || failure.get() != null }
        assertNull(failure.get())
    }
}
