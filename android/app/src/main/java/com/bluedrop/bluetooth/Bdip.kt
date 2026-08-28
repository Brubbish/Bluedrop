package com.bluedrop.bluetooth

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * BDIP v1 wire framing — see docs/PROTOCOL.md.
 *
 * Frame: magic "BDIP" | ver u8 | type u8 | length u32 LE | payload.
 * All multi-byte integers are unsigned little-endian.
 */
object Bdip {
    const val MAGIC = "BDIP"
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 10
    const val MAX_PAYLOAD: Int = 8 * 1024 * 1024 // 8 MiB frame cap (protocol)
    const val MAX_TEXT: Int = 1024 * 1024        // 1 MiB clipboard text send cap
    const val MAX_IMAGE: Int = MAX_PAYLOAD       // 8 MiB clipboard image cap
    const val MAX_FILE: Long = 512L * 1024 * 1024 // 512 MiB single-file policy cap
    const val CHUNK_SIZE: Int = 60 * 1024        // 60 KiB file chunks
    const val PING_INTERVAL_MS: Long = 10_000
    const val RX_DEAD_MS: Long = 30_000
    const val HELLO_TIMEOUT_MS: Long = 10_000

    const val TYPE_HELLO: Int = 0x01
    const val TYPE_PING: Int = 0x02
    const val TYPE_PONG: Int = 0x03
    const val TYPE_BYE: Int = 0x04
    const val TYPE_CLIPBOARD_TEXT: Int = 0x10
    const val TYPE_CLIPBOARD_IMAGE: Int = 0x11
    const val TYPE_FILE_META: Int = 0x20
    const val TYPE_FILE_CHUNK: Int = 0x21
    const val TYPE_FILE_ACK: Int = 0x22
    const val TYPE_PROGRESS: Int = 0x30

    const val BYE_SHUTDOWN: Int = 0
    const val BYE_PROTOCOL_ERROR: Int = 1
    const val BYE_BUSY: Int = 2
    const val BYE_PAIRING_FAILED: Int = 3

    val MAGIC_BYTES = MAGIC.toByteArray(Charsets.US_ASCII)

    class ProtocolException(message: String) : IOException(message)
}

data class Frame(val type: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Frame && other.type == type && other.payload.contentEquals(payload)

    override fun hashCode(): Int = type * 31 + payload.contentHashCode()
}

object FrameCodec {
    /** Serializes a frame; deterministic and allocation-bounded by payload size. */
    fun encode(type: Int, payload: ByteArray): ByteArray {
        require(payload.size <= Bdip.MAX_PAYLOAD) { "payload over frame cap" }
        val out = ByteArray(Bdip.HEADER_SIZE + payload.size)
        out[0] = Bdip.MAGIC_BYTES[0]
        out[1] = Bdip.MAGIC_BYTES[1]
        out[2] = Bdip.MAGIC_BYTES[2]
        out[3] = Bdip.MAGIC_BYTES[3]
        out[4] = Bdip.VERSION.toByte()
        out[5] = type.toByte()
        val len = payload.size
        out[6] = (len and 0xFF).toByte()
        out[7] = (len shr 8 and 0xFF).toByte()
        out[8] = (len shr 16 and 0xFF).toByte()
        out[9] = (len shr 24 and 0xFF).toByte()
        payload.copyInto(out, Bdip.HEADER_SIZE)
        return out
    }

    /**
     * Reads one full frame. Throws [Bdip.ProtocolException] on bad magic,
     * unknown version, or over-cap length; throws [EOFException] on a clean
     * EOF at a frame boundary; other IOExceptions map to link loss.
     */
    fun read(input: InputStream): Frame {
        val header = readFully(input, Bdip.HEADER_SIZE)
        if (header[0] != Bdip.MAGIC_BYTES[0] || header[1] != Bdip.MAGIC_BYTES[1] ||
            header[2] != Bdip.MAGIC_BYTES[2] || header[3] != Bdip.MAGIC_BYTES[3]
        ) {
            throw Bdip.ProtocolException("bad magic")
        }
        val version = header[4].toInt() and 0xFF
        if (version != Bdip.VERSION) {
            throw Bdip.ProtocolException("unsupported version $version")
        }
        val type = header[5].toInt() and 0xFF
        val length = (header[6].toInt() and 0xFF) or
            ((header[7].toInt() and 0xFF) shl 8) or
            ((header[8].toInt() and 0xFF) shl 16) or
            ((header[9].toInt() and 0xFF) shl 24)
        if (length > Bdip.MAX_PAYLOAD) {
            throw Bdip.ProtocolException("frame length $length over cap")
        }
        val payload = if (length == 0) ByteArray(0) else readFully(input, length)
        return Frame(type, payload)
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = input.read(buf, off, count - off)
            if (n < 0) throw EOFException("stream ended mid-frame")
            off += n
        }
        return buf
    }

    /** u32 LE writer used by FILE_CHUNK headers. */
    fun u32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte(),
    )
}
