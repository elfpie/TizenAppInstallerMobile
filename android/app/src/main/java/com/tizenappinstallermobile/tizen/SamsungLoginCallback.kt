package com.tizenappinstallermobile.tizen

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Loopback-only Samsung form callback. Handles preconnects without blocking the real POST. */
class SamsungLoginCallback(requestedPort: Int = 4794,
    private val checkCancelled: () -> Unit = {},
    private val onEvent: (String, Map<String, Any>) -> Unit = { _, _ -> },
) : Closeable {
    val state = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val result = CompletableFuture<JSONObject>()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val servers = mutableListOf<ServerSocket>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val acceptors = Executors.newFixedThreadPool(2)
    private val workers = java.util.concurrent.ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(16))
    val port: Int
    init {
        try {
            servers.add(bind("127.0.0.1", requestedPort))
            port = servers.first().localPort
            servers.add(bind("::1", port))
            servers.forEach { server -> acceptors.execute {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                    sockets.add(socket)
                    if (closed.get()) { sockets.remove(socket); socket.close(); break }
                    try { workers.execute { handle(socket) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { sockets.remove(socket); socket.close() }
                }
            } }
            onEvent("samsung.callback.listening", mapOf("address_family" to "ipv4_ipv6"))
        } catch (error: Exception) { close(); throw error }
    }
    private fun bind(address: String, port: Int) = ServerSocket().apply {
        try { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName(address), port)) }
        catch (error: Exception) { close(); throw error }
    }
    fun await(timeoutMs: Long = 300000): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            checkCancelled()
            try { return result.get(250, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.TimeoutException) { }
        }
        error("Samsung sign-in timed out after five minutes. Try again or import your certificate files.")
    }
    fun fail(error: Exception) { result.completeExceptionally(error) }
    private fun params(encoded: String): Map<String, String> = encoded.split('&').filter { it.isNotEmpty() }.associate {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
    }
    private fun handle(socket: Socket) {
        socket.use {
            socket.soTimeout = 5000
            var receivedRequest = false
            try {
                val input = socket.getInputStream()
                fun line(): String {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) {
                        val byte = input.read()
                        require(byte >= 0 && bytes.size() < 8192) { "Invalid callback request" }
                        if (byte == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                        bytes.write(byte)
                    }
                }
                val request = line().split(' ')
                require(request.size == 3)
                receivedRequest = true
                val headers = mutableMapOf<String, String>()
                var headerSize = 0
                while (true) {
                    val next = line()
                    if (next.isEmpty()) break
                    headerSize += next.length
                    require(headerSize < 16384)
                    val pair = next.split(':', limit = 2)
                    if (pair.size == 2) headers[pair[0].lowercase()] = pair[1].trim()
                }
                require(request[1].substringBefore('?') == "/signin/callback")
                val query = params(request[1].substringAfter('?', ""))
                val body = when (request[0]) {
                    "GET" -> emptyMap()
                    "POST" -> {
                        require(headers["content-type"].orEmpty().startsWith("application/x-www-form-urlencoded"))
                        val size = headers["content-length"]?.toInt() ?: 0
                        require(size in 1..65536)
                        params(SdbClient.readExactly(input, size).toString(Charsets.UTF_8))
                    }
                    else -> error("Invalid callback method")
                }
                val fields = query + body
                require(query["state"] == null || body["state"] == null || query["state"] == body["state"])
                val auth = JSONObject(fields["code"] ?: error("Missing callback code"))
                require((fields["state"] ?: auth.optString("state")) == state)
                require(auth.optString("access_token").isNotBlank() && auth.optString("userId").isNotBlank())
                respond(socket, 200, "Sign-in complete. Return to Tizen Pocket.")
                onEvent("samsung.callback.accepted", mapOf("method" to request[0], "address_family" to if (socket.inetAddress is java.net.Inet6Address) "ipv6" else "ipv4"))
                result.complete(auth)
            } catch (_: Exception) {
                if (receivedRequest) {
                    onEvent("samsung.callback.rejected", mapOf("reason" to "invalid_payload"))
                    runCatching { respond(socket, 400, "Invalid sign-in response. Please retry sign-in from Tizen Pocket.") }
                }
            } finally { sockets.remove(socket) }
        }
    }
    private fun respond(socket: Socket, code: Int, text: String) {
        val body = "<!doctype html><html><meta name=\"viewport\" content=\"width=device-width\"><body><h2>$text</h2></body></html>".toByteArray()
        socket.getOutputStream().write(("HTTP/1.1 $code ${if (code == 200) "OK" else "Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        servers.forEach { runCatching { it.close() } }
        sockets.forEach { runCatching { it.close() } }
        acceptors.shutdownNow(); workers.shutdownNow()
        // A blocked JVM accept can retain the listening FD until its thread unwinds.
        // Wait before allowing the next sign-in to bind the same callback port.
        try {
            acceptors.awaitTermination(2, TimeUnit.SECONDS)
            workers.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
