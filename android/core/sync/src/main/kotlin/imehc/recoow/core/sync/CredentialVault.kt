package imehc.recoow.core.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import imehc.recoow.core.network.text
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.*

data class Credentials(val connectionId: String, val endpoint: String, val account: String,
    val serverDevice: String, val token: String, val recoveryKey: String? = null, val canPair: Boolean = false) {
    override fun toString() = "Credentials(connectionId=$connectionId)"
}

interface CredentialStore {
    fun load(): Credentials?
    fun save(credentials: Credentials?)
    val localDeviceId: String
}

/** Keystore key and ciphertext stay outside database/preference rollback bundles and Android backup. */
class CredentialVault(context: Context, filename: String = "sync-credentials") : CredentialStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, filename))
    private val alias = "${context.packageName}.sync.credentials.v1"
    private val installation = context.getSharedPreferences("sync-device", Context.MODE_PRIVATE)
    override val localDeviceId: String
        get() = synchronized(this) {
            installation.getString("uuid", null) ?: UUID.randomUUID().toString().also {
                check(installation.edit().putString("uuid", it).commit())
            }
        }

    @Synchronized override fun load(): Credentials? {
        if (!file.baseFile.exists()) return null
        val raw = Json.parseToJsonElement(file.readFully().decodeToString()).jsonObject
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(raw.text("iv"), Base64.NO_WRAP)))
        cipher.updateAAD(alias.toByteArray())
        val bytes = cipher.doFinal(Base64.decode(raw.text("ciphertext"), Base64.NO_WRAP))
        val value = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        return Credentials(value.text("connectionId"), value.text("endpoint"), value.text("account"), value.text("serverDevice"),
            value.text("token"), value["recoveryKey"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
            value["canPair"]?.jsonPrimitive?.booleanOrNull == true)
    }

    @Synchronized override fun save(credentials: Credentials?) {
        if (credentials == null) { file.delete(); return }
        val plain = buildJsonObject {
            put("connectionId", credentials.connectionId); put("endpoint", credentials.endpoint); put("account", credentials.account)
            put("serverDevice", credentials.serverDevice); put("token", credentials.token)
            put("recoveryKey", credentials.recoveryKey?.let(::JsonPrimitive) ?: JsonNull)
            put("canPair", credentials.canPair)
        }.toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(alias.toByteArray())
        val encrypted = cipher.doFinal(plain)
        val raw = buildJsonObject {
            put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }.toString().toByteArray()
        val output = file.startWrite()
        try { output.write(raw); file.finishWrite(output) } catch (error: Throwable) { file.failWrite(output); throw error }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
