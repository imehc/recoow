package imehc.recoow.core.location

import android.content.Context
import android.os.SystemClock
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 仅作用于地图的兜底方案：既不改系统 DNS 设置，也不改应用的 API 客户端。 */
internal object MapNetwork {
    private var installed = false

    @Synchronized fun install(context: Context) {
        if (installed) return
        val bootstrap = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
        val httpsDns = DnsOverHttps.Builder().client(bootstrap)
            .url("https://dns.alidns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("223.5.5.5"), InetAddress.getByName("223.6.6.6"))
            .includeIPv6(false).build()
        val dns = MapDns(Dns.SYSTEM, httpsDns)
        val client = OkHttpClient.Builder().dns(dns).connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .cache(Cache(File(context.applicationContext.cacheDir, "map-http"), 64L * 1024 * 1024))
            .build()
        HttpRequestUtil.setOkHttpClient(client)
        installed = true
    }
}

internal class MapDns(private val system: Dns, private val fallback: Dns) : Dns {
    private var cached: List<InetAddress>? = null
    private var expires = 0L

    @Synchronized override fun lookup(hostname: String): List<InetAddress> {
        if (hostname != "tiles.openfreemap.org") return system.lookup(hostname)
        val now = SystemClock.elapsedRealtime()
        cached?.takeIf { now < expires }?.let { return it }
        return try { system.lookup(hostname) } catch (original: UnknownHostException) {
            try {
                fallback.lookup(hostname).also { addresses ->
                    if (addresses.isEmpty()) throw UnknownHostException(hostname)
                    cached = addresses
                    expires = SystemClock.elapsedRealtime() + 30_000
                }
            } catch (failed: Exception) {
                original.addSuppressed(failed)
                throw original
            }
        }
    }
}
