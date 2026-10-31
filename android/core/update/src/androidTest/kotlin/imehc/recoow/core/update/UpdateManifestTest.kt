package imehc.recoow.core.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import imehc.recoow.core.domain.update.AppUpdateException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateManifestTest {
    private fun manifest() = JSONObject().apply {
        put("schemaVersion", 1); put("packageName", "imehc.recoow"); put("abi", "arm64-v8a")
        put("versionName", "0.6.1"); put("versionCode", 7)
        put("downloadUrl", "https://github.com/imehc/recoow/releases/download/android/v0.6.1/recoow.apk")
        put("sha256", "a".repeat(64)); put("sizeBytes", 1000); put("minSdk", 26); put("notes", "修复问题")
    }

    @Test fun readsDistributionManifest() {
        val release = AndroidAppUpdates.parseManifest(manifest().toString())
        UpdatePolicy.validate(release)
        assertEquals(7L, release.versionCode)
        assertEquals("修复问题", release.notes)
    }

    @Test fun rejectsCoercedAndOutOfRangeNumbers() {
        for (field in listOf("schemaVersion", "versionCode", "sizeBytes", "minSdk")) {
            for (value in listOf<Any>("7", 7.5, true, JSONObject.NULL)) {
                assertThrows(AppUpdateException::class.java) {
                    AndroidAppUpdates.parseManifest(manifest().put(field, value).toString())
                }
            }
        }
        assertThrows(AppUpdateException::class.java) {
            AndroidAppUpdates.parseManifest(manifest().put("minSdk", Long.MAX_VALUE).toString())
        }
    }

    @Test fun rejectsWrongProductSchemaAndAbi() {
        for ((field, value) in listOf("packageName" to "other.app", "abi" to "x86_64", "schemaVersion" to 2)) {
            assertThrows(AppUpdateException::class.java) {
                AndroidAppUpdates.parseManifest(manifest().put(field, value).toString())
            }
        }
    }
}
