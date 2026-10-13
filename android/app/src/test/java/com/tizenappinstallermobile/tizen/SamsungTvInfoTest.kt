package com.tizenappinstallermobile.tizen

import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
class SamsungTvInfoTest {
    private val json = """{"device":{"OS":"Tizen","type":"Samsung SmartTV","name":"55&quot; Neo QLED","modelName":"QN55QNX1DAGXZD","developerMode":"1","developerIP":"192.168.15.3"}}"""
    @Test fun readsTvMetadataWithoutAnSdbConnection() {
        val server = ServerSocket(0)
        val pool = Executors.newSingleThreadExecutor()
        val task = pool.submit {
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val reader = socket.getInputStream().bufferedReader()
                assertTrue(reader.readLine().startsWith("GET /api/v2/ "))
                while (!reader.readLine().isNullOrEmpty()) {}
                val bytes = json.toByteArray()
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
            }
        }
        try {
            val tv = SamsungTvInfo.probe("127.0.0.1", OkHttpClient(), port = server.localPort)!!
            assertEquals("55\" Neo QLED", tv.name); assertEquals(true, tv.developerMode)
            assertEquals("192.168.15.3", tv.developerHost)
            task.get(5, TimeUnit.SECONDS)
        } finally { server.close(); pool.shutdownNow() }
    }
    @Test fun unrelatedHttpDevicesAreNotTvMatches() {
        assertNull(SamsungTvInfo.parse("""{"device":{"OS":"Android","type":"SmartTV"}}"""))
        assertNull(SamsungTvInfo.parse("""{"status":"ok"}"""))
    }
}
