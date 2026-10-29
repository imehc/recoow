package imehc.recoow.core.location

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.domain.location.GeoCoordinate
import imehc.recoow.core.domain.location.LocationObservation
import java.io.FileInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AndroidLocationSourceTest {
    @get:Rule val compose = createComposeRule()

    @Suppress("DEPRECATION") // The provider API and app-op query also run on API 26.
    @Test fun observesNativeGPSProviderAndCanSubscribeAgainAfterCancellation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        compose.setContent { }
        compose.waitForIdle()
        val manager = context.getSystemService(LocationManager::class.java)
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val previousMode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        fun setMockMode(mode: String) {
            automation.executeShellCommand("appops set ${context.packageName} android:mock_location $mode").use {
                FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
            }
        }
        setMockMode("allow")
        try {
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, false, false, false,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            val source = AndroidLocationSource(context)
            runBlocking {
                repeat(2) { attempt ->
                    withTimeout(10_000) {
                        val result = async { source.observe(0, 0f).filterIsInstance<LocationObservation.Fix>().first() }
                        val point = GeoCoordinate(39.907 + attempt * 0.001, 116.397)
                        while (!result.isCompleted) {
                            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
                                latitude = point.latitude
                                longitude = point.longitude
                                accuracy = 3f
                                time = System.currentTimeMillis()
                                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                            })
                            delay(100)
                        }
                        assertEquals(point, result.await().location.coordinate)
                    }
                }
            }
        } finally {
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            setMockMode(when (previousMode) {
                AppOpsManager.MODE_ALLOWED -> "allow"
                AppOpsManager.MODE_IGNORED -> "ignore"
                AppOpsManager.MODE_ERRORED -> "deny"
                else -> "default"
            })
        }
    }
}
