package imehc.recoow.core.network

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.*

class SyncHttpException(val status: Int, val resetRequired: Boolean = false) : IOException("HTTP $status")
class SyncEndpointException : IllegalArgumentException("Invalid server address")

/** No ambient credentials or mutable interceptor: one API instance belongs to one connection. */
interface SyncApi {
    suspend fun get(path: String, query: Map<String, String> = emptyMap()): ByteArray
    suspend fun delete(path: String): ByteArray
    suspend fun post(path: String, body: JsonObject): ByteArray
    suspend fun upload(key: String, checksum: String, type: String, file: File): ByteArray
    suspend fun download(key: String, file: File, limit: Long)
}

internal interface RetrofitEndpoints {
    @GET @Streaming suspend fun get(@Url path: String, @QueryMap query: Map<String, String>): Response<ResponseBody>
    @DELETE @Streaming suspend fun delete(@Url path: String): Response<ResponseBody>
    @POST @Streaming suspend fun post(@Url path: String, @Body body: RequestBody): Response<ResponseBody>
    @PUT @Streaming suspend fun put(@Url path: String, @QueryMap query: Map<String, String>, @Body body: RequestBody): Response<ResponseBody>
}

interface SyncApiProvider {
    fun endpoint(value: String): String
    fun create(endpoint: String, token: String? = null): SyncApi
}

class SyncApiFactory(private val allowLocalHttp: Boolean = false) : SyncApiProvider {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS).build()

    override fun endpoint(value: String): String {
        try {
            val url = value.trim().toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
            val local = url.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1")
            require(url.isHttps || allowLocalHttp && local)
            return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build().toString()
        } catch (_: IllegalArgumentException) {
            // URL parser errors can contain credentials from the supplied address.
            throw SyncEndpointException()
        }
    }

    override fun create(endpoint: String, token: String?): SyncApi {
        val base = endpoint(endpoint).toHttpUrl()
        val scoped = client.newBuilder().addInterceptor { chain ->
            val original = chain.request()
            check(original.url.scheme == base.scheme && original.url.host == base.host && original.url.port == base.port)
            val request = original.newBuilder().header("Accept", "application/json")
            if (token != null) request.header("Authorization", "Bearer $token")
            chain.proceed(request.build())
        }.build()
        val retrofit = Retrofit.Builder().baseUrl(base).client(scoped).build().create(RetrofitEndpoints::class.java)
        return object : SyncApi {
            override suspend fun get(path: String, query: Map<String, String>) = read(retrofit.get(path, query))
            override suspend fun delete(path: String) = read(retrofit.delete(path))
            override suspend fun post(path: String, body: JsonObject) = read(retrofit.post(path, body.toString().toRequestBody("application/json".toMediaType())))
            override suspend fun upload(key: String, checksum: String, type: String, file: File) =
                read(retrofit.put("api/media/objects/$key", mapOf("checksum" to checksum, "contentType" to type), file.asRequestBody(type.toMediaType())))
            override suspend fun download(key: String, file: File, limit: Long) {
                val body = checked(retrofit.get("api/media/objects/$key", emptyMap()))
                withContext(Dispatchers.IO) {
                    body.use {
                        require(it.contentLength() <= limit) { "Media exceeds declared size" }
                        // v2 may reuse identical bytes with different MIME metadata. Identity is SHA-256 + size.
                        require(it.contentType() != null) { "Invalid media content type" }
                        it.byteStream().use { input -> file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024); var count = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer); if (read < 0) break
                                count += read; require(count <= limit) { "Media exceeds declared size" }
                                output.write(buffer, 0, read)
                            }
                            output.fd.sync()
                        } }
                    }
                }
            }
        }
    }

    private suspend fun read(response: Response<ResponseBody>): ByteArray = withContext(Dispatchers.IO) {
        checked(response).use { body ->
            // Large pages contain full snapshots. Bound allocation without truncating accepted data.
            val max = 64L * 1024 * 1024
            require(body.contentLength() <= max) { "Sync response too large" }
            body.byteStream().use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer); if (count < 0) break
                    require(out.size().toLong() + count <= max) { "Sync response too large" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        }
    }

    private fun checked(response: Response<ResponseBody>): ResponseBody {
        if (response.isSuccessful) return requireNotNull(response.body())
        // Never surface response bodies (which may contain credentials or user records) to logs/UI.
        val error = response.errorBody()?.use { it.byteStream().readBytesLimited(4096).decodeToString() }
        throw SyncHttpException(response.code(), response.code() == 409 && error?.contains("reset_required") == true)
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val buffer = ByteArray(limit); var count = 0
        while (count < limit) { val size = read(buffer, count, limit - count); if (size < 0) break; count += size }
        return buffer.copyOf(count)
    }
}
