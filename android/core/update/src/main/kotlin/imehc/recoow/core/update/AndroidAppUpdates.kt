package imehc.recoow.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import imehc.recoow.core.domain.update.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

class UpdateFileProvider : FileProvider()

@Singleton
class AndroidAppUpdates @Inject constructor(@ApplicationContext private val context: Context) : AppUpdates {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            if (!chain.request().url.isHttps) throw IOException("Update transport must use HTTPS")
            chain.proceed(chain.request())
        }.build()
    private val downloadLock = Mutex()
    private val directory get() = File(context.cacheDir, "app-updates/verified").apply { mkdirs() }

    @Suppress("DEPRECATION")
    private fun packageInfo() = context.packageManager.getPackageInfo(context.packageName, signatureFlags)
    override val installed: InstalledAppVersion
        get() = packageInfo().let { InstalledAppVersion(it.versionName.orEmpty(), it.code) }

    override suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        try {
            response(UpdatePolicy.MANIFEST_URL).use { response ->
                // 仓库还没有首个发布版本时，没有可更新的目标。
                if (response.code == 404) return@withContext null
                if (!response.isSuccessful) throw AppUpdateException(AppUpdateProblem.CONNECTION)
                val bytes = response.body.byteStream().use { input ->
                    val result = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (result.size() + count > 64 * 1024) throw AppUpdateException(AppUpdateProblem.INVALID_RELEASE)
                        result.write(buffer, 0, count)
                    }
                    result.toByteArray()
                }
                val release = parseManifest(bytes.toString(Charsets.UTF_8))
                UpdatePolicy.validate(release)
                if (release.versionCode <= installed.code) return@withContext null
                if (Build.VERSION.SDK_INT < release.minSdk || "arm64-v8a" !in Build.SUPPORTED_ABIS)
                    throw AppUpdateException(AppUpdateProblem.INCOMPATIBLE)
                release
            }
        } catch (error: IOException) {
            throw AppUpdateException(AppUpdateProblem.CONNECTION, error)
        }
    }

    override suspend fun download(release: AppRelease, progress: (Long, Long) -> Unit): PreparedAppUpdate =
        downloadLock.withLock { withContext(Dispatchers.IO) {
            UpdatePolicy.validate(release)
            val destination = apk(release)
            if (destination.isFile && runCatching { verify(destination, release) }.isSuccess)
                return@withContext PreparedAppUpdate(release)
            val staging = File(context.cacheDir, "app-updates/${release.sha256}.part")
            try {
                response(release.downloadUrl).use { response ->
                    if (!response.isSuccessful) throw AppUpdateException(AppUpdateProblem.CONNECTION)
                    val length = response.body.contentLength()
                    if (length != -1L && length != release.sizeBytes)
                        throw AppUpdateException(AppUpdateProblem.INVALID_RELEASE)
                    response.body.byteStream().use { input -> staging.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var notified = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > release.sizeBytes) throw AppUpdateException(AppUpdateProblem.INVALID_RELEASE)
                            output.write(buffer, 0, count)
                            val percent = (total * 100 / release.sizeBytes).toInt()
                            if (percent != notified) { progress(total, release.sizeBytes); notified = percent }
                        }
                        if (total != release.sizeBytes) throw AppUpdateException(AppUpdateProblem.INVALID_RELEASE)
                        output.fd.sync()
                    } }
                }
                verify(staging, release)
                if (!staging.renameTo(destination)) throw AppUpdateException(AppUpdateProblem.STORAGE)
                directory.listFiles()?.filter { it != destination && it.extension == "apk" }?.forEach { it.delete() }
                PreparedAppUpdate(release)
            } catch (error: IOException) {
                throw AppUpdateException(AppUpdateProblem.CONNECTION, error)
            } finally { staging.delete() }
        } }

    override suspend fun installationUri(update: PreparedAppUpdate): String = withContext(Dispatchers.IO) {
        val file = apk(update.release)
        verify(file, update.release)
        FileProvider.getUriForFile(context, "${context.packageName}.updates", file).toString()
    }

    private fun apk(release: AppRelease): File {
        UpdatePolicy.validate(release)
        return File(directory, "${release.sha256}.apk")
    }

    private fun response(url: String): Response = client.newCall(Request.Builder().url(url)
        .header("Accept", "application/octet-stream").build()).execute()

    @Suppress("DEPRECATION")
    private fun verify(file: File, release: AppRelease) {
        if (!file.isFile || file.length() != release.sizeBytes)
            throw AppUpdateException(AppUpdateProblem.INVALID_RELEASE)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        if (digest.digest().hex() != release.sha256) throw AppUpdateException(AppUpdateProblem.INVALID_RELEASE)
        val candidate = context.packageManager.getPackageArchiveInfo(file.path, signatureFlags)
            ?: throw AppUpdateException(AppUpdateProblem.INVALID_RELEASE)
        val current = packageInfo()
        UpdatePolicy.validatePackage(release, candidate.packageName, current.packageName,
            candidate.code, current.code, candidate.applicationInfo?.minSdkVersion ?: 0, current.signers, candidate.signers)
    }

    private val signatureFlags get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
    private val PackageInfo.code get() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()
    private val PackageInfo.signers: Set<String> get() {
        val signatures = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") this.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    internal companion object {
        fun parseManifest(text: String): AppRelease = try {
            val json = JSONObject(text)
            if (json.integer("schemaVersion") != 1L || json.getString("packageName") != "imehc.recoow" ||
                json.getString("abi") != "arm64-v8a") throw AppUpdateException(AppUpdateProblem.INVALID_RELEASE)
            val minSdk = json.integer("minSdk")
            if (minSdk !in 26..Int.MAX_VALUE) throw AppUpdateException(AppUpdateProblem.INVALID_RELEASE)
            AppRelease(json.getString("versionName"), json.integer("versionCode"), json.getString("downloadUrl"),
                json.getString("sha256"), json.integer("sizeBytes"), minSdk.toInt(), json.optString("notes"))
        } catch (error: org.json.JSONException) { throw AppUpdateException(AppUpdateProblem.INVALID_RELEASE, error) }

        private fun JSONObject.integer(key: String): Long = when (val value = get(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw AppUpdateException(AppUpdateProblem.INVALID_RELEASE)
        }
    }
}

@Module @InstallIn(SingletonComponent::class)
abstract class AppUpdateModule {
    @Binds @Singleton abstract fun bindUpdates(value: AndroidAppUpdates): AppUpdates
}
