package com.bluedrop.bluetooth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Full-duplex session tests over in-memory pipes: two BdipSessions wired
 * cross-wise, exercising the real reader/writer/heartbeat coroutines.
 */
class BdipSessionTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var initiator: BdipSession
    private lateinit var responder: BdipSession
    private lateinit var responderListener: RecordingListener
    private lateinit var initiatorListener: RecordingListener

    private class RecordingListener : BdipSession.Listener {
        val texts = mutableListOf<String>()
        val images = mutableListOf<ByteArray>()
        val metas = mutableListOf<FileMeta>()
        val acks = mutableListOf<FileAck>()
        val chunks = mutableListOf<Pair<Int, ByteArray>>()
        var closedReason: String? = null
        var peer: HelloInfo? = null

        override fun onEstablished(peer: HelloInfo) {
            this.peer = peer
        }

        override fun onClipboardText(text: String) {
            texts += text
        }

        override fun onClipboardImage(png: ByteArray) {
            images += png
        }

        override fun onFileMeta(meta: FileMeta) {
            metas += meta
        }

        override fun onFileChunk(index: Int, data: ByteArray) {
            chunks += index to data
        }

        override fun onFileAck(ack: FileAck) {
            acks += ack
        }

        override fun onClosed(reason: String) {
            closedReason = reason
        }
    }

    /** Receiver that acknowledges chunks exactly like the service does. */
    private class AckingReceiver(private val session: () -> BdipSession) : BdipSession.Listener {
        var bytes = 0L
        lateinit var meta: FileMeta
        val received = ByteArrayOutputStream()

        override fun onEstablished(peer: HelloInfo) {}
        override fun onClipboardText(text: String) {}
        override fun onClipboardImage(png: ByteArray) {}
        override fun onFileAck(ack: FileAck) {}

        override fun onFileMeta(meta: FileMeta) {
            this.meta = meta
            bytes = 0
        }

        override fun onFileChunk(index: Int, data: ByteArray) {
            received.writeBytes(data)
            bytes += data.size
            val session = session()
            session.sendFileAck(FileAck(id = meta.id, received = bytes))
            if (bytes >= meta.size) {
                session.sendFileAck(FileAck(id = meta.id, received = bytes, done = true))
            }
        }

        override fun onClosed(reason: String) {}
    }

    @Before
    fun setUp(): Unit = runBlocking {
        val aToB = pipe()
        val bToA = pipe()

        responderListener = RecordingListener()
        initiatorListener = RecordingListener()

        initiator = BdipSession(
            scope, aToB.first, bToA.second, {}, initiatorListener,
            HelloInfo(name = " initiator "), BdipSession.Role.INITIATOR,
        )
        responder = BdipSession(
            scope, bToA.first, aToB.second, {}, responderListener,
            HelloInfo(name = "responder", caps = listOf("text", "image", "file")),
            BdipSession.Role.RESPONDER,
        )
        initiator.start()
        responder.start()
        withTimeout(5_000) {
            initiator.awaitEstablished()
            responder.awaitEstablished()
        }
    }

    @After
    fun tearDown() {
        initiator.shutdown()
        responder.shutdown()
        scope.cancel()
    }

    private fun pipe(): Pair<PipedInputStream, PipedOutputStream> {
        val input = PipedInputStream(1 shl 20)
        val output = PipedOutputStream(input)
        return input to output
    }

    @Test
    fun `handshake exchanges hello`() {
        assertEquals("responder", initiatorListener.peer?.name)
        assertEquals(" initiator ", responderListener.peer?.name)
        assertTrue(initiator.isEstablished && responder.isEstablished)
    }

    @Test
    fun `clipboard text flows both ways`() = runBlocking {
        assertTrue(initiator.sendClipboardText("hello from initiator"))
        assertTrue(responder.sendClipboardText("hi from responder"))
        withTimeout(5_000) {
            while (responderListener.texts.isEmpty() || initiatorListener.texts.isEmpty()) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(listOf("hello from initiator"), responderListener.texts)
        assertEquals(listOf("hi from responder"), initiatorListener.texts)
    }

    @Test
    fun `clipboard image round-trips bytes`() = runBlocking {
        val png = ByteArray(100_000) { (it % 253).toByte() }
        assertTrue(initiator.sendClipboardImage(png))
        withTimeout(5_000) {
            while (responderListener.images.isEmpty()) kotlinx.coroutines.delay(10)
        }
        assertArrayEquals(png, responderListener.images.first())
    }

    @Test
    fun `oversized text is refused locally`() {
        val tooBig = "x".repeat(Bdip.MAX_TEXT + 1)
        assertEquals(false, initiator.sendClipboardText(tooBig))
    }

    @Test
    fun `file transfer stalls without receiver acks`() = runBlocking {
        initiator.chunkAckTimeoutMs = 300
        val data = ByteArray(150_000) { (it % 199).toByte() } // 3 chunks at 60 KiB
        val meta = FileMeta(
            id = "t1", name = "blob.bin", size = data.size.toLong(),
            chunks = 3, mime = "application/octet-stream",
        )
        // the recording listener never ACKs, so stop-and-wait must time out
        val sent = initiator.sendFile(
            meta,
            { offset, count -> data.copyOfRange(offset.toInt(), (offset + count).toInt()) }
        )
        assertEquals(-1L, sent)
        assertEquals(1, responderListener.metas.size)
        assertEquals(1, responderListener.chunks.size) // stopped after first un-acked chunk
        Unit
    }

    @Test
    fun `file transfer completes with acking receiver`() = runBlocking {
        val data = ByteArray(150_000) { (it % 199).toByte() }
        val meta = FileMeta(
            id = "t2", name = "blob.bin", size = data.size.toLong(),
            chunks = 3, mime = "application/octet-stream",
        )
        val aToB = pipe()
        val bToA = pipe()
        lateinit var responder2: BdipSession
        val receiver2 = AckingReceiver { responder2 }
        val initiator2 = BdipSession(
            scope, aToB.first, bToA.second, {}, RecordingListener(),
            HelloInfo(name = "i2"), BdipSession.Role.INITIATOR,
        )
        responder2 = BdipSession(
            scope, bToA.first, aToB.second, {}, receiver2,
            HelloInfo(name = "r2"), BdipSession.Role.RESPONDER,
        )
        initiator2.start()
        responder2.start()
        withTimeout(5_000) { initiator2.awaitEstablished() }

        val sent = withTimeout(30_000) {
            initiator2.sendFile(
                meta,
                { offset, count -> data.copyOfRange(offset.toInt(), (offset + count).toInt()) }
            )
        }
        assertEquals(data.size.toLong(), sent)
        assertArrayEquals(data, receiver2.received.toByteArray())
        initiator2.shutdown()
        responder2.shutdown()
    }

    @Test
    fun `cancelling a send aborts with an error ack to the receiver`() = runBlocking {
        val data = ByteArray(200_000) { (it % 197).toByte() } // 4 chunks
        val meta = FileMeta(
            id = "cancel1", name = "abort.bin", size = data.size.toLong(),
            chunks = 4, mime = "application/octet-stream",
        )
        // the responder records but never acks, so the send stalls after chunk 0
        val sendJob = scope.launch {
            initiator.sendFile(
                meta,
                { offset, count -> data.copyOfRange(offset.toInt(), (offset + count).toInt()) }
            )
        }
        withTimeout(5_000) {
            while (responderListener.chunks.isEmpty()) kotlinx.coroutines.delay(10)
        }
        sendJob.cancel()
        withTimeout(5_000) {
            while (responderListener.acks.none { it.error != null && it.id == "cancel1" }) {
                kotlinx.coroutines.delay(10)
            }
        }
        val abort = responderListener.acks.first { it.error != null && it.id == "cancel1" }
        assertEquals("cancelled by sender", abort.error)
    }

    @Test
    fun `bye closes both sides`() = runBlocking {
        responder.shutdown("done")
        withTimeout(5_000) {
            while (initiatorListener.closedReason == null) kotlinx.coroutines.delay(10)
        }
        assertTrue(initiatorListener.closedReason!!.contains("bye"))
    }
}
