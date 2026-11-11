package imehc.recoow.core.network

import imehc.recoow.core.network.generated.models.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** 生成的账户 DTO 与同步接口共用同一套请求头来源和字段映射。 */
class AccountApi(private val api: SyncApi) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun identity(): RWAPIIdentityResponse? = try {
        decodeIdentity(api.get("api/users/me"))
    } catch (error: SyncHttpException) { if (error.status == 404) null else throw error }

    suspend fun bind(password: String): RWAPIIdentityResponse = decodeIdentity(
        api.post("api/users/me/credentials", json.parseToJsonElement(json.encodeToString(RWAPIBindCredentialsRequest(password))).jsonObject))

    suspend fun change(current: String, next: String) {
        api.put("api/users/me/password", json.parseToJsonElement(json.encodeToString(RWAPIPasswordRequest(current, next))).jsonObject)
    }

    suspend fun reset(recovery: String, next: String): RWAPIIdentityResponse = decodeIdentity(
        api.post("api/password-resets", json.parseToJsonElement(json.encodeToString(RWAPIPasswordResetRequest(recovery.trim(), next))).jsonObject))

    suspend fun login(username: String, password: String, device: String): RWAPIDeviceLoginResponse {
        val body = json.parseToJsonElement(json.encodeToString(RWAPIDeviceLoginRequest(username.trim(), password, device.trim().ifBlank { "Android" }))).jsonObject
        val response = json.decodeFromString<RWAPIDeviceLoginResponse>(APIFields.rename(
            Json.parseToJsonElement(api.post("api/device-logins", body).decodeToString()), false).toString())
        require(listOf(response.userId, response.username, response.accountId, response.deviceId, response.deviceToken).all { it.isNotBlank() })
        return response
    }

    private fun decodeIdentity(bytes: ByteArray): RWAPIIdentityResponse {
        val response = json.decodeFromString<RWAPIIdentityResponse>(APIFields.rename(Json.parseToJsonElement(bytes.decodeToString()), false).toString())
        require(listOf(response.userId, response.username, response.accountId).all { it.isNotBlank() })
        return response
    }
}
