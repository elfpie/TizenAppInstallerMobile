package com.tizenappinstallermobile.tizen

import java.net.InetAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class SamsungLoginCallbackTest {
    private fun form(state: String) = "state=$state&code=" + URLEncoder.encode(
        "{\"access_token\":\"test-token\",\"userId\":\"test-user\"}", "UTF-8")
    private fun send(server: SamsungLoginCallback, address: String, request: String): String =
        Socket(InetAddress.getByName(address), server.port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(request.toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
    private fun post(server: SamsungLoginCallback, address: String, body: String, query: String = "") =
        send(server, address, "POST /signin/callback$query HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")

    @Test fun browserPostWorksOverIpv6WhileAnIpv4PreconnectIsIdle() {
        SamsungLoginCallback(0).use { server ->
            Socket("127.0.0.1", server.port).use {
                assertTrue(post(server, "::1", form(server.state)).startsWith("HTTP/1.1 200"))
                assertEquals("test-user", server.await(1000).getString("userId"))
            }
        }
    }
    @Test fun invalidStateDoesNotConsumeLoginAndTokensAreAbsentFromHtml() {
        SamsungLoginCallback(0).use { server ->
            assertTrue(post(server, "127.0.0.1", form("wrong-state")).startsWith("HTTP/1.1 400"))
            val response = post(server, "127.0.0.1", form(server.state))
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertFalse(response.contains("test-token"))
            assertEquals("test-token", server.await(1000).getString("access_token"))
        }
    }
    @Test fun callbackSupportsStateInQueryAndCodeInPostButRejectsConflicts() {
        SamsungLoginCallback(0).use { server ->
            assertTrue(post(server, "::1", form("wrong-state"), "?state=${server.state}").startsWith("HTTP/1.1 400"))
            assertTrue(post(server, "::1", form(server.state).substringAfter('&'), "?state=${server.state}").startsWith("HTTP/1.1 200"))
            assertEquals("test-user", server.await(1000).getString("userId"))
        }
    }
    @Test fun getCallbackWorksAndCancelledListenerReleasesBothAddresses() {
        val cancelled = AtomicBoolean(false)
        val server = SamsungLoginCallback(0, { if (cancelled.get()) throw CancellationException() })
        val port = server.port
        server.use {
            cancelled.set(true)
            assertThrows(CancellationException::class.java) { server.await(1000) }
        }
        SamsungLoginCallback(port).use { replacement ->
            val response = send(replacement, "127.0.0.1", "GET /signin/callback?${form(replacement.state)} HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertTrue(response.startsWith("HTTP/1.1 200"))
            replacement.await(1000)
        }
    }
}
