package imehc.recoow.core.database

import android.content.Context
import kotlinx.serialization.json.*

data class LocalClientSource(val deviceId: String, val deviceName: String, val appVersion: String, val osVersion: String) {
    fun profile(): JsonObject = buildJsonObject {
        put("client", "android"); put("os", "android"); put("appVersion", appVersion); put("osVersion", osVersion)
        put("localDeviceId", deviceId); put("deviceName", deviceName)
    }
    fun origin(originalDeviceId: String): String? = if (originalDeviceId != deviceId) null else buildJsonObject {
        put("client", "android"); put("os", "android"); put("appVersion", appVersion); put("osVersion", osVersion)
        put("deviceId", deviceId); put("deviceName", deviceName)
    }.toString()

    companion object {
        fun current(context: Context, deviceId: String): LocalClientSource {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return LocalClientSource(deviceId, android.os.Build.MODEL.take(160), info.versionName ?: "unknown", android.os.Build.VERSION.RELEASE)
        }
    }
}
