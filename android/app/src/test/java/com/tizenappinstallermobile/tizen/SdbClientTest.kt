package com.tizenappinstallermobile.tizen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SdbClientTest {
    @Test fun fragmentedFrameAndBadChecksum() {
        val original = SdbClient.Frame("WRTE", 1, 2, "hello".toByteArray())
        val bytes = SdbClient.encode(original)
        val fragmented = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(length, 3))
        }
        assertArrayEquals(original.payload, SdbClient.decode(fragmented).payload)
        bytes[24] = 0
        assertThrows(IllegalArgumentException::class.java) { SdbClient.decode(ByteArrayInputStream(bytes)) }
    }
    private fun fakeTv(serverAction: (Socket) -> Unit, clientAction: (Int) -> Unit) {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val cnxn = SdbClient.decode(socket.getInputStream())
                assertEquals("CNXN", cnxn.command)
                socket.getOutputStream().write(SdbClient.encode(SdbClient.Frame("CNXN", 0x01000000, 4096, "device::TV".toByteArray())))
                serverAction(socket)
            }
        }
        try { clientAction(server.localPort); task.get(10, TimeUnit.SECONDS) }
        finally { server.close(); executor.shutdownNow() }
    }
    @Test fun shellUsesFlowControlAndWaitsForClose() = fakeTv({ socket ->
        val input = socket.getInputStream(); val output = socket.getOutputStream()
        val open = SdbClient.decode(input)
        assertEquals("shell:0 vd_applist\u0000", open.payload.toString(Charsets.UTF_8))
        output.write(SdbClient.encode(SdbClient.Frame("OKAY", 7, open.arg0)))
        output.write(SdbClient.encode(SdbClient.Frame("WRTE", 7, open.arg0, "apps count: 0".toByteArray())))
        assertEquals("OKAY", SdbClient.decode(input).command)
        output.write(SdbClient.encode(SdbClient.Frame("CLSE", 7, open.arg0)))
        assertEquals("CLSE", SdbClient.decode(input).command)
    }, { port ->
        SdbClient("127.0.0.1", port).use { assertEquals("apps count: 0", it.shell("0 vd_applist")) }
    })
    @Test fun uploadSendsModeAndTimestampInDoneHeader() {
        val content = ByteArray(140000) { (it % 255).toByte() }
        val file = File.createTempFile("sdb-upload", ".wgt").apply { writeBytes(content) }
        try {
            fakeTv({ socket ->
                val input = socket.getInputStream(); val output = socket.getOutputStream()
                val open = SdbClient.decode(input)
                assertEquals("sync:\u0000", open.payload.toString(Charsets.UTF_8))
                output.write(SdbClient.encode(SdbClient.Frame("OKAY", 9, open.arg0)))
                val wire = ByteArrayOutputStream()
                var done = false
                var parsed = 0
                val received = ByteArrayOutputStream()
                while (!done) {
                    val frame = SdbClient.decode(input)
                    assertEquals("WRTE", frame.command)
                    wire.write(frame.payload)
                    output.write(SdbClient.encode(SdbClient.Frame("OKAY", 9, open.arg0)))
                    val bytes = wire.toByteArray()
                    while (bytes.size - parsed >= 8) {
                        val name = bytes.copyOfRange(parsed, parsed + 4).toString(Charsets.US_ASCII)
                        val value = ByteBuffer.wrap(bytes, parsed + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        if (name == "DONE") {
                            assertEquals((file.lastModified() / 1000).toInt(), value)
                            assertEquals(parsed + 8, bytes.size)
                            done = true
                            break
                        }
                        if (bytes.size - parsed < 8 + value) break
                        if (name == "SEND") assertEquals("/tmp/test.wgt,33188", bytes.copyOfRange(parsed + 8, parsed + 8 + value).toString(Charsets.UTF_8))
                        else { assertEquals("DATA", name); received.write(bytes, parsed + 8, value) }
                        parsed += 8 + value
                    }
                }
                assertArrayEquals(content, received.toByteArray())
                output.write(SdbClient.encode(SdbClient.Frame("WRTE", 9, open.arg0, SdbClient.syncHeader("OKAY", 0))))
                assertEquals("OKAY", SdbClient.decode(input).command)
                assertEquals("CLSE", SdbClient.decode(input).command)
            }, { port -> SdbClient("127.0.0.1", port).use { it.push(file, "/tmp/test.wgt") {} } })
        } finally { file.delete() }
    }
    @Test fun samsungZeroIdCloseCompletesCapabilitiesThenDuidOnSameConnection() = fakeTv({ socket ->
        val input = socket.getInputStream(); val output = socket.getOutputStream()
        for ((service, payload) in listOf(
            "capability:" to (byteArrayOf(0, 0) + "platform_version:8.0\nprofile_name:tv\n".toByteArray()),
            "shell:0 getduid" to "TV-TEST-DUID".toByteArray())) {
            val open = SdbClient.decode(input)
            assertEquals(service + "\u0000", open.payload.toString(Charsets.UTF_8))
            output.write(SdbClient.encode(SdbClient.Frame("OKAY", 17, open.arg0)))
            output.write(SdbClient.encode(SdbClient.Frame("WRTE", 17, open.arg0, payload)))
            assertEquals("OKAY", SdbClient.decode(input).command)
            output.write(SdbClient.encode(SdbClient.Frame("CLSE", 0, open.arg0)))
            val close = SdbClient.decode(input)
            assertEquals("CLSE", close.command); assertEquals(17, close.arg1)
        }
    }, { port ->
        SdbClient("127.0.0.1", port).use {
            assertEquals("8.0", it.capabilities()["platform_version"])
            assertEquals("TV-TEST-DUID", it.shell("0 getduid"))
        }
    })
    @Test fun wrongRemoteIdOnDataIsStillRejected() = fakeTv({ socket ->
        val input = socket.getInputStream(); val output = socket.getOutputStream()
        val open = SdbClient.decode(input)
        output.write(SdbClient.encode(SdbClient.Frame("OKAY", 17, open.arg0)))
        output.write(SdbClient.encode(SdbClient.Frame("WRTE", 99, open.arg0, "wrong-channel".toByteArray())))
        assertEquals("CLSE", SdbClient.decode(input).command)
    }, { port ->
        SdbClient("127.0.0.1", port).use { client ->
            assertThrows(IllegalArgumentException::class.java) { client.shell("0 getduid") }
        }
    })
    @Test fun delayedCloseFromPreviousServiceDoesNotBreakNextOpen() = fakeTv({ socket ->
        val input = socket.getInputStream(); val output = socket.getOutputStream()
        val first = SdbClient.decode(input)
        output.write(SdbClient.encode(SdbClient.Frame("OKAY", 17, first.arg0)))
        val recv = SdbClient.decode(input)
        assertEquals("WRTE", recv.command)
        assertEquals("RECV", recv.payload.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        output.write(SdbClient.encode(SdbClient.Frame("OKAY", 17, first.arg0)))
        output.write(SdbClient.encode(SdbClient.Frame("WRTE", 17, first.arg0,
            SdbClient.syncHeader("FAIL", 3) + "den".toByteArray())))
        assertEquals("OKAY", SdbClient.decode(input).command)
        assertEquals("CLSE", SdbClient.decode(input).command)
        val next = SdbClient.decode(input)
        output.write(SdbClient.encode(SdbClient.Frame("CLSE", 0, first.arg0)))
        output.write(SdbClient.encode(SdbClient.Frame("OKAY", 18, next.arg0)))
        output.write(SdbClient.encode(SdbClient.Frame("WRTE", 18, next.arg0, "TV-TEST-DUID".toByteArray())))
        assertEquals("OKAY", SdbClient.decode(input).command)
        output.write(SdbClient.encode(SdbClient.Frame("CLSE", 0, next.arg0)))
        assertEquals("CLSE", SdbClient.decode(input).command)
    }, { port ->
        SdbClient("127.0.0.1", port).use { client ->
            assertThrows(IllegalStateException::class.java) { client.pull("/missing/author-signature.xml") }
            assertEquals("TV-TEST-DUID", client.shell("0 getduid"))
        }
    })
}
