package imehc.recoow.core.network

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaPresenceTest {
    private fun check(status: String, checksum: String, size: Long, expected: Boolean? = null) = runBlocking {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit<String> {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val first = requireNotNull(reader.readLine())
                        while (!reader.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Length: $size\r\nETag: \"$checksum\"\r\nConnection: close\r\n\r\n").toByteArray())
                        first
                    }
                }
                val api = SyncApiFactory(true).create("http://127.0.0.1:${server.localPort}/","test-token")
                if (expected != null) assertEquals(expected,api.mediaExists("sync/hash","hash",4))
                else try { api.mediaExists("sync/hash","hash",4); fail("Wrong identity accepted") } catch (_: IllegalArgumentException) { }
                assertEquals("HEAD /api/media/objects/sync/hash HTTP/1.1",request.get(5,TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun existingObjectSkipsByteUpload() { check("200 OK","hash",4,true) }
    @Test fun missingObjectMustBeUploaded() { check("404 Not Found","hash",0,false) }
    @Test fun wrongChecksumIsRejected() { check("200 OK","other",4) }
    @Test fun wrongSizeIsRejected() { check("200 OK","hash",5) }
}
