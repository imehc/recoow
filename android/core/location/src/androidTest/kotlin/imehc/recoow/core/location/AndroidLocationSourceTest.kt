package imehc.recoow.core.location

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
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
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Suppress("DEPRECATION") // The provider API and app-op query also run on API 26.
    @Test fun observesNativeGPSProviderAndCanSubscribeAgainAfterCancellation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        compose.setContent { }
        compose.waitForIdle()
        val manager = context.getSystemService(LocationManager::class.java)
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val previousMode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        fun shell(command: String): String = automation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).use { input -> input.readBytes().toString(Charsets.UTF_8).trim() }
        }
        fun setMockMode(mode: String) { shell("appops set ${context.packageName} android:mock_location $mode") }
        // Mock providers still respect the device-wide location switch on physical devices.
        val modernLocationCommand = Build.VERSION.SDK_INT >= 30
        val previousLocationState = if (modernLocationCommand) shell("cmd location is-location-enabled")
            else shell("settings get secure location_mode")
        check(if (modernLocationCommand) previousLocationState in setOf("true", "false")
            else previousLocationState == "null" || previousLocationState.toIntOrNull() in 0..3)
        fun locationState(value: String) {
            shell(when {
                modernLocationCommand -> "cmd location set-location-enabled $value"
                value == "null" -> "settings delete secure location_mode"
                else -> "settings put secure location_mode $value"
            })
        }
        setMockMode("allow")
        try {
            locationState(if (modernLocationCommand) "true" else "3")
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, false, false, false,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            check(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "Test GPS provider is disabled" }
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
            try { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            finally {
                try { locationState(previousLocationState) }
                finally { setMockMode(when (previousMode) {
                    AppOpsManager.MODE_ALLOWED -> "allow"
                    AppOpsManager.MODE_IGNORED -> "ignore"
                    AppOpsManager.MODE_ERRORED -> "deny"
                    else -> "default"
                }) }
            }
        }
    }
}
