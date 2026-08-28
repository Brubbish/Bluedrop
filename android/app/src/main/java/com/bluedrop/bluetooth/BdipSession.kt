package com.bluedrop.bluetooth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One BDIP session over an established socket (see docs/PROTOCOL.md).
 *
 * Transport-agnostic (plain streams + a close callback) so it can be unit
 * tested with in-memory pipes. All writes are serialized through a single
 * writer coroutine, as the spec requires.
 */
class BdipSession(
    private val scope: CoroutineScope,
    private val input: InputStream,
    private val output: OutputStream,
    private val closeTransport: () -> Unit,
    private val listener: Listener,
    private val hello: HelloInfo,
    private val role: Role,
) {
    enum class Role { INITIATOR, RESPONDER }

    interface Listener {
        fun onEstablished(peer: HelloInfo)
        fun onClipboardText(text: String)
        fun onClipboardImage(png: ByteArray)
        fun onFileMeta(meta: FileMeta)
        fun onFileChunk(index: Int, data: ByteArray)
        fun onFileAck(ack: FileAck)
        fun onClosed(reason: String)
    }

    private val writeChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val ackChannel = Channel<FileAck>(Channel.UNLIMITED)
    private val pendingHello = Channel<Frame>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private val established = CompletableDeferred<Unit>()

    @Volatile private var lastSentAt = 0L
    @Volatile private var lastReceivedAt = 0L
    private var readerJob: Job? = null
    private var writerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var handshakeJob: Job? = null

    /** Per-chunk ACK wait; overridable so tests can shorten it. */
    var chunkAckTimeoutMs: Long = 60_000

    /** HELLO exchange timeout; overridable so tests can extend it on slow runners. */
    var helloTimeoutMs: Long = Bdip.HELLO_TIMEOUT_MS

    val isEstablished: Boolean get() = established.isCompleted

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        val now = System.currentTimeMillis()
        lastSentAt = now
        lastReceivedAt = now
        writerJob = scope.launch {
            for (bytes in writeChannel) {
                output.write(bytes)
                output.flush()
                lastSentAt = System.currentTimeMillis()
            }
        }
        readerJob = scope.launch { runReader() }
        heartbeatJob = scope.launch { runHeartbeat() }
        handshakeJob = scope.launch { runHandshake() }
    }

    fun shutdown(detail: String = "") {
        sendBye(Bdip.BYE_SHUTDOWN, detail)
        tearDown("bye sent")
    }

    private fun fail(message: String) {
        sendBye(Bdip.BYE_PROTOCOL_ERROR, message)
        tearDown(message)
    }

    private fun sendBye(reason: Int, detail: String) {
        try {
            val payload = byteArrayOf(reason.toByte()) +
                ("{\"message\":\"$detail\"}").toByteArray(Charsets.UTF_8)
            writeChannel.trySend(FrameCodec.encode(Bdip.TYPE_BYE, payload))
            // give the writer a moment to flush the BYE before hard close
            Thread.sleep(150)
        } catch (_: Exception) {
        }
    }

    private fun tearDown(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        readerJob?.cancel()
        writerJob?.cancel()
        heartbeatJob?.cancel()
        handshakeJob?.cancel()
        writeChannel.close()
        ackChannel.close()
        pendingHello.close()
        established.cancel()
        try {
            closeTransport()
        } catch (_: Exception) {
        }
        listener.onClosed(reason)
    }

    // ---------------------------------------------------------------- handshake

    private suspend fun runHandshake() {
        try {
            if (role == Role.INITIATOR) {
                writeChannel.send(FrameCodec.encode(Bdip.TYPE_HELLO, hello.encode()))
                lastSentAt = System.currentTimeMillis()
            }
            val peer = withTimeoutOrNull(helloTimeoutMs) { pendingHello.receive() }
            if (peer == null || peer.type != Bdip.TYPE_HELLO) {
                fail("hello timeout")
                return
            }
            val info = try {
                HelloInfo.decode(peer.payload)
            } catch (e: Exception) {
                fail("bad HELLO payload: ${e.message}")
                return
            }
            if (hello.token.isNotEmpty() && info.token.isNotEmpty() && hello.token != info.token) {
                fail("pairing token mismatch")
                return
            }
            if (role == Role.RESPONDER) {
                writeChannel.send(FrameCodec.encode(Bdip.TYPE_HELLO, hello.encode()))
                lastSentAt = System.currentTimeMillis()
            }
            established.complete(Unit)
            listener.onEstablished(info)
        } catch (e: Exception) {
            if (!closed.get()) fail("handshake failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- loops

    private suspend fun runReader() {
        try {
            while (!closed.get()) {
                val frame = FrameCodec.read(input)
                lastReceivedAt = System.currentTimeMillis()
                if (!established.isCompleted) {
                    when (frame.type) {
                        Bdip.TYPE_HELLO -> {
                            pendingHello.send(frame)
                            continue
                        }

                        Bdip.TYPE_BYE -> {
                            val reason = frame.payload.getOrNull(0)?.toInt() ?: 0
                            tearDown("peer bye ($reason)")
                            return
                        }

                        else -> {
                            fail("expected HELLO first, got type 0x${frame.type.toString(16)}")
                            return
                        }
                    }
                }
                when (frame.type) {
                    Bdip.TYPE_HELLO -> { /* duplicate HELLO after establish: ignore */ }
                    Bdip.TYPE_PING -> writeChannel.send(
                        FrameCodec.encode(Bdip.TYPE_PONG, frame.payload)
                    )

                    Bdip.TYPE_BYE -> {
                        val reason = frame.payload.getOrNull(0)?.toInt() ?: 0
                        tearDown("peer bye ($reason)")
                        return
                    }

                    Bdip.TYPE_CLIPBOARD_TEXT ->
                        listener.onClipboardText(String(frame.payload, Charsets.UTF_8))

                    Bdip.TYPE_CLIPBOARD_IMAGE -> listener.onClipboardImage(frame.payload)

                    Bdip.TYPE_FILE_META -> runCatching { FileMeta.decode(frame.payload) }
                        .onSuccess { listener.onFileMeta(it) }

                    Bdip.TYPE_FILE_CHUNK -> if (frame.payload.size >= 4) {
                        val index = (frame.payload[0].toInt() and 0xFF) or
                            ((frame.payload[1].toInt() and 0xFF) shl 8) or
                            ((frame.payload[2].toInt() and 0xFF) shl 16) or
                            ((frame.payload[3].toInt() and 0xFF) shl 24)
                        listener.onFileChunk(
                            index,
                            frame.payload.copyOfRange(4, frame.payload.size),
                        )
                    }

                    Bdip.TYPE_FILE_ACK -> runCatching { FileAck.decode(frame.payload) }
                        .onSuccess {
                            ackChannel.send(it)
                            listener.onFileAck(it)
                        }

                    else -> { /* unknown type: skip per spec */ }
                }
            }
        } catch (e: Bdip.ProtocolException) {
            if (!closed.get()) fail(e.message ?: "protocol error")
        } catch (e: Exception) {
            if (!closed.get()) tearDown("link lost: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun runHeartbeat() {
        while (!closed.get()) {
            delay(1_000)
            if (closed.get()) return
            val now = System.currentTimeMillis()
            if (now - lastReceivedAt >= Bdip.RX_DEAD_MS) {
                tearDown("rx dead after ${Bdip.RX_DEAD_MS} ms")
                return
            }
            if (now - lastSentAt >= Bdip.PING_INTERVAL_MS) {
                writeChannel.send(
                    FrameCodec.encode(Bdip.TYPE_PING, ByteArray(8) { it.toByte() })
                )
                lastSentAt = now
            }
        }
    }

    // ---------------------------------------------------------------- senders

    /** Sends clipboard text; refuses payloads over the send cap. */
    fun sendClipboardText(text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > Bdip.MAX_TEXT) return false
        return writeChannel.trySend(FrameCodec.encode(Bdip.TYPE_CLIPBOARD_TEXT, bytes)).isSuccess
    }

    fun sendClipboardImage(png: ByteArray): Boolean {
        if (png.size > Bdip.MAX_IMAGE) return false
        return writeChannel.trySend(FrameCodec.encode(Bdip.TYPE_CLIPBOARD_IMAGE, png)).isSuccess
    }

    suspend fun awaitEstablished() = established.await()

    /**
     * Streams a file using stop-and-wait chunking (spec §3.4). Returns the
     * number of bytes acknowledged, or -1 on failure.
     */
    suspend fun sendFile(
        meta: FileMeta,
        readChunk: (offset: Long, count: Int) -> ByteArray,
        onProgress: ((received: Long, total: Long) -> Unit)? = null,
    ): Long {
        writeChannel.send(FrameCodec.encode(Bdip.TYPE_FILE_META, meta.encode()))
        var offset = 0L
        var index = 0
        while (offset < meta.size) {
            val count = minOf(Bdip.CHUNK_SIZE.toLong(), meta.size - offset).toInt()
            val chunk = readChunk(offset, count)
            if (chunk.size != count) return -1
            writeChannel.send(
                FrameCodec.encode(Bdip.TYPE_FILE_CHUNK, FrameCodec.u32(index) + chunk)
            )
            val ack = withTimeoutOrNull(chunkAckTimeoutMs) { ackChannel.receive() } ?: return -1
            if (ack.id != meta.id || ack.error != null) return -1
            offset += count
            index++
            onProgress?.invoke(offset, meta.size)
            if (ack.done == true) return meta.size // receiver combined the final ack
        }
        // the receiver's final ack carries done=true
        val done = withTimeoutOrNull(chunkAckTimeoutMs) { ackChannel.receive() } ?: return -1
        if (done.done != true || done.error != null) return -1
        return meta.size
    }

    fun sendFileAck(ack: FileAck) {
        writeChannel.trySend(FrameCodec.encode(Bdip.TYPE_FILE_ACK, ack.encode()))
    }
}
