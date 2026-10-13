package com.tizenappinstallermobile.tizen

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/** Direct SDB wire transport. One service at a time, with WRTE/OKAY flow control. */
class SdbClient(
    host: String,
    port: Int = 26101,
    connectTimeout: Int = 5000,
    private val register: (Closeable) -> Unit = {},
    private val checkCancelled: () -> Unit = {},
) : Closeable {
    private val socket = Socket()
    private var maxPayload = 4096
    private var nextId = 1
    val banner: String

    init {
        register(socket)
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeout)
            socket.soTimeout = 15000
            socket.tcpNoDelay = true
            send(Frame("CNXN", 0x01000000, maxPayload, "host::tizen-mobile\u0000".toByteArray()))
            val reply = receive()
            require(reply.command != "AUTH") {
                "TV requested SDB authentication. Enable Developer Mode, set its host IP to this phone, and restart the TV."
            }
            require(reply.command == "CNXN") { "Not an SDB device (unexpected ${reply.command})." }
            maxPayload = if (reply.arg1 == 0) 4096 else reply.arg1.coerceIn(1, 4096)
            banner = reply.payload.toString(Charsets.UTF_8).trimEnd('\u0000')
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    data class Frame(val command: String, val arg0: Int, val arg1: Int, val payload: ByteArray = byteArrayOf())

    companion object {
        fun encode(frame: Frame): ByteArray {
            val command = ByteBuffer.wrap(frame.command.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int
            return ByteBuffer.allocate(24 + frame.payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(frame.arg0).putInt(frame.arg1).putInt(frame.payload.size)
                .putInt(frame.payload.sumOf { it.toInt() and 255 }).putInt(command.inv()).put(frame.payload).array()
        }

        fun decode(input: InputStream): Frame {
            val header = ByteBuffer.wrap(readExactly(input, 24)).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val length = header.int
            val checksum = header.int
            require(header.int == command.inv()) { "Invalid SDB frame magic." }
            require(length in 0..(1024 * 1024)) { "Invalid SDB payload length." }
            val bytes = readExactly(input, length)
            require(checksum == bytes.sumOf { it.toInt() and 255 }) { "Invalid SDB checksum." }
            val name = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array().toString(Charsets.US_ASCII)
            return Frame(name, arg0, arg1, bytes)
        }

        fun readExactly(input: InputStream, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(bytes, offset, count - offset)
                check(read > 0) { "TV disconnected unexpectedly." }
                offset += read
            }
            return bytes
        }

        fun syncHeader(command: String, value: Int): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put(command.toByteArray(Charsets.US_ASCII)).putInt(value).array()
    }

    private fun send(frame: Frame) {
        checkCancelled()
        socket.getOutputStream().write(encode(frame))
        socket.getOutputStream().flush()
    }

    private fun receive(): Frame {
        checkCancelled()
        return decode(socket.getInputStream())
    }

    private inner class Channel(service: String) : Closeable {
        val localId = nextId++
        var remoteId = 0
        var closed = false
        val pending = ArrayDeque<ByteArray>()
        var current = byteArrayOf()
        var cursor = 0

        init {
            send(Frame("OPEN", localId, 0, (service.trimEnd('\u0000') + "\u0000").toByteArray()))
            var reply = receive()
            while (reply.command == "CLSE" && reply.arg1 != localId) reply = receive()
            check(reply.command == "OKAY" && reply.arg1 == localId && reply.arg0 != 0) {
                "TV refused service $service. Check Developer Mode and the configured host IP."
            }
            remoteId = reply.arg0
        }

        fun accept(frame: Frame) {
            // A peer can send a final CLSE for the previous service after we have opened a new one.
            if (frame.arg1 != localId) return
            require(frame.arg0 == remoteId) { "Invalid SDB channel ID." }
            when (frame.command) {
                "WRTE" -> {
                    pending.add(frame.payload)
                    send(Frame("OKAY", localId, remoteId))
                }
                "CLSE" -> {
                    if (!closed) send(Frame("CLSE", localId, remoteId))
                    closed = true
                }
                "OKAY" -> Unit
                else -> error("Unexpected SDB command ${frame.command}.")
            }
        }

        fun write(bytes: ByteArray) {
            for (offset in bytes.indices step maxPayload) {
                check(!closed) { "TV closed the upload channel." }
                send(Frame("WRTE", localId, remoteId, bytes.copyOfRange(offset, minOf(offset + maxPayload, bytes.size))))
                while (true) {
                    val frame = receive()
                    accept(frame)
                    if (frame.command == "OKAY" && frame.arg1 == localId) break
                    check(!closed) { "TV closed the upload channel." }
                }
            }
        }

        fun read(): ByteArray? {
            while (pending.isEmpty() && !closed) accept(receive())
            return if (pending.isEmpty()) null else pending.removeFirst()
        }

        fun exact(count: Int): ByteArray {
            val result = ByteArray(count)
            var offset = 0
            while (offset < count) {
                if (cursor >= current.size) {
                    current = read() ?: error("TV ended the upload response early.")
                    cursor = 0
                }
                val size = minOf(count - offset, current.size - cursor)
                current.copyInto(result, offset, cursor, cursor + size)
                cursor += size
                offset += size
            }
            return result
        }

        override fun close() {
            if (!closed) {
                send(Frame("CLSE", localId, remoteId))
                closed = true
            }
        }
    }

    fun service(name: String, timeoutMs: Int = 15000, onChunk: (String) -> Unit = {}): ByteArray {
        socket.soTimeout = timeoutMs
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        return Channel(name).use { channel ->
            val output = ByteArrayOutputStream()
            while (true) {
                check(System.nanoTime() < deadline) { "TV command timed out." }
                val chunk = channel.read() ?: break
                require(output.size() + chunk.size <= 4 * 1024 * 1024) { "TV returned too much output." }
                output.write(chunk)
                onChunk(chunk.toString(Charsets.UTF_8))
            }
            output.toByteArray()
        }
    }

    fun shell(command: String, timeoutMs: Int = 15000, onChunk: (String) -> Unit = {}): String =
        service("shell:$command", timeoutMs, onChunk).toString(Charsets.UTF_8)

    fun capabilities(): Map<String, String> {
        val response = service("capability:")
        // Samsung prefixes the capability text with a two-byte length.
        val text = if (response.size >= 2) response.copyOfRange(2, response.size).toString(Charsets.UTF_8) else ""
        return text.trimEnd('\u0000').lines().mapNotNull {
            val parts = it.trim().split(':', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    fun push(file: File, path: String, onProgress: (Double) -> Unit) {
        socket.soTimeout = 30000
        Channel("sync:").use { channel ->
            val remote = "$path,33188".toByteArray() // regular file, 0644
            channel.write(syncHeader("SEND", remote.size) + remote)
            var sent = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    channel.write(syncHeader("DATA", count) + buffer.copyOf(count))
                    sent += count
                    onProgress(sent.toDouble() / file.length() * 100)
                }
            }
            // DONE's integer is the mtime, not a payload length; no body follows it.
            channel.write(syncHeader("DONE", (file.lastModified() / 1000).toInt()))
            val header = channel.exact(8)
            val name = header.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            val length = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(length in 0..65536) { "Invalid upload response." }
            if (name == "FAIL") error("TV rejected upload: ${channel.exact(length).toString(Charsets.UTF_8)}")
            check(name == "OKAY" && length == 0) { "Unexpected upload response $name." }
            onProgress(100.0)
        }
    }

    fun pull(path: String, limit: Int = 1024 * 1024): ByteArray {
        require(path.startsWith('/') && path.none { it.code < 32 } && path.split('/').none { it == ".." }) { "Unsafe TV file path." }
        socket.soTimeout = 15000
        return Channel("sync:").use { channel ->
            val name = path.toByteArray()
            channel.write(syncHeader("RECV", name.size) + name)
            val output = ByteArrayOutputStream()
            while (true) {
                val header = channel.exact(8)
                val command = header.copyOfRange(0, 4).toString(Charsets.US_ASCII)
                val size = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                require(size in 0..65536) { "Invalid TV file response." }
                if (command == "DONE") break
                if (command == "FAIL") error("TV file is not readable: " + channel.exact(size).toString(Charsets.UTF_8))
                check(command == "DATA" && output.size().toLong() + size <= limit) { "TV file exceeds its read limit." }
                output.write(channel.exact(size))
            }
            output.toByteArray()
        }
    }

    override fun close() = socket.close()
}
