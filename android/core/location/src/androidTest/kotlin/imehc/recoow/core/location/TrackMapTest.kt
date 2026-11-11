package imehc.recoow.core.location

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import imehc.recoow.core.domain.location.GeoCoordinate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import androidx.test.platform.app.InstrumentationRegistry
import org.maplibre.android.RenderingEngine
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.io.File
import android.graphics.Bitmap

class TrackMapTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mapDnsFallbackDoesNotChangeOtherRequests() {
        val calls = AtomicInteger()
        val address = InetAddress.getByAddress(byteArrayOf(104, 26, 7, 23))
        val dns = MapDns(Dns { throw UnknownHostException(it) }, Dns { calls.incrementAndGet(); listOf(address) })
        assertEquals(listOf(address), dns.lookup("tiles.openfreemap.org"))
        assertEquals(listOf(address), dns.lookup("tiles.openfreemap.org"))
        assertEquals(1, calls.get())
        try { dns.lookup("api.recoow.example"); throw AssertionError("Other requests must keep system DNS") }
        catch (_: UnknownHostException) { assertEquals(1, calls.get()) }
    }

    /** 需显式开启的提供方冒烟测试；常规设备测试不依赖任何公共服务。 */
    @Test fun rendersOnlineBasemapWhenRequested() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMap") == "true")
        val loaded = AtomicInteger()
        val first = AtomicInteger()
        val failure = AtomicReference<String?>()
        val route = listOf(GeoCoordinate(39.907, 116.397), GeoCoordinate(39.912, 116.402))
        compose.setContent {
            TrackMap(MapStyle("https://tiles.openfreemap.org/styles/liberty"), route, 0xff4483cf.toInt(), Modifier.fillMaxSize(),
                onFirstFrame = { first.incrementAndGet() }, onRendered = { loaded.incrementAndGet() }, onFailure = { failure.set(it) },
                focus = MapFocus(route, "online"), requireBasemapTiles = true)
        }
        compose.waitUntil(60_000) { loaded.get() > 0 }
        assertTrue(first.get() > 0)
        assertNull(failure.get())
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir, "online-map.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun rendersLocalMapWithoutGMSAndReleasesNativeView() {
        val style = MapStyle("""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#eeeeee"}}]}""")
        val visible = mutableStateOf(true)
        val ready = AtomicInteger()
        val frames = AtomicInteger()
        val firstFrames = AtomicInteger()
        val failure = AtomicReference<String?>()
        val focus = mutableStateOf(MapFocus(listOf(GeoCoordinate(39.907, 116.397)), "start"))
        val route = (0..2000).map { GeoCoordinate(39.907 + it * 0.000001, 116.397 + it * 0.000001) }
        compose.setContent {
            if (visible.value) TrackMap(style, route,
                0xff6650a4.toInt(), Modifier.fillMaxSize(), onReady = { ready.incrementAndGet() },
                onRendered = { frames.incrementAndGet() }, onFailure = { failure.set(it) },
                lines = listOf(MapLine(route.take(1001), 0xff27ae60.toInt()), MapLine(route.drop(1000), 0xff2980b9.toInt())),
                markers = listOf(MapMarker(route.first(), 0xff6650a4.toInt()), MapMarker(route.last(), 0xffe67e22.toInt())), focus = focus.value,
                onFirstFrame = { firstFrames.incrementAndGet() })
        }
        compose.waitUntil(20_000) { ready.get() == 1 && frames.get() > 0 || failure.get() != null }
        assertNull(failure.get())
        compose.waitUntil(5_000) { firstFrames.get() == 1 }
        val supportsVulkan = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x400003)
        assertEquals(if (supportsVulkan) RenderingEngine.Type.VULKAN else RenderingEngine.Type.OPENGL,
            RenderingEngine.getCurrentType())
        compose.runOnIdle { focus.value = MapFocus(listOf(route.last()), "end") }
        compose.waitForIdle()
        assertEquals(1, firstFrames.get())
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        frames.set(0)
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(20_000) { ready.get() == 2 && frames.get() > 0 || failure.get() != null }
        assertNull(failure.get())
        compose.waitUntil(5_000) { firstFrames.get() == 2 }
    }
}
