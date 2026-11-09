package imehc.recoow.core.network

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AccountApiTest {
    private class RecordingApi(var response: String) : SyncApi {
        var path = ""
        var body = buildJsonObject { }
        var method = ""
        override suspend fun get(path: String, query: Map<String, String>): ByteArray { this.path = path; method = "GET"; return response.toByteArray() }
        override suspend fun post(path: String, body: JsonObject): ByteArray { this.path = path; this.body = body; method = "POST"; return response.toByteArray() }
        override suspend fun put(path: String, body: JsonObject): ByteArray { this.path = path; this.body = body; method = "PUT"; return response.toByteArray() }
        override suspend fun delete(path: String): ByteArray = error("Unexpected deletion")
        override suspend fun upload(key: String, checksum: String, type: String, file: File): ByteArray = error("Unexpected upload")
        override suspend fun download(key: String, file: File, limit: Long) = error("Unexpected download")
    }
    private val identity = """{"user_id":"user","username":"u123456","account_id":"existing-account"}"""

    @Test fun portableServerScopeMatchesIosCanonicalVector() {
        val normalized = SyncApiFactory().endpoint("https://EXAMPLE.com:443/base/")
        assertEquals("https://example.com/base/", normalized)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("982ea881d40b49fa1b0a33a9f937c4d27fd857d9e5c9556221b87391ceea1c30", digest)
    }

    @Test fun bindingKeepsAccountAndOmitsAutomaticUsername() = runBlocking {
        val transport = RecordingApi(identity)
        val result = AccountApi(transport).bind("密码-with-spaces ")
        assertEquals("existing-account", result.accountId)
        assertEquals("api/users/me/credentials", transport.path)
        assertEquals(setOf("password"), transport.body.keys)
        assertEquals("密码-with-spaces ", transport.body.text("password"))
    }

    @Test fun changeAndRecoveryUseCanonicalFieldsWithoutTrimmingPassword() = runBlocking {
        val transport = RecordingApi("""{"ok":true}""")
        val account = AccountApi(transport)
        account.change(" original-password ", " replacement-password ")
        assertEquals("PUT", transport.method)
        assertEquals("api/users/me/password", transport.path)
        assertEquals(setOf("current_password", "new_password"), transport.body.keys)
        assertEquals(" replacement-password ", transport.body.text("new_password"))
        transport.response = identity
        account.reset(" Recovery-Key ", " replacement-password ")
        assertEquals("api/password-resets", transport.path)
        assertEquals(setOf("recovery_key", "new_password"), transport.body.keys)
        assertEquals("Recovery-Key", transport.body.text("recovery_key"))
        assertEquals(" replacement-password ", transport.body.text("new_password"))
    }

    @Test fun loginUsesDeviceCredentialAndRejectsMalformedIdentity() = runBlocking {
        val transport = RecordingApi("""{"user_id":"u","username":"name","account_id":"a","device_id":"d","device_token":"token"}""")
        val api = AccountApi(transport)
        val response = api.login(" name ", "password ", " phone ")
        assertEquals("api/device-logins", transport.path)
        assertEquals("token", response.deviceToken)
        assertEquals(setOf("username", "password", "device_name"), transport.body.keys)
        assertEquals("password ", transport.body.text("password"))
        transport.response = """{"user_id":"u","username":"name","account_id":"","device_id":"d","device_token":"token"}"""
        try { api.login("name", "password", "phone"); fail("Invalid account accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
