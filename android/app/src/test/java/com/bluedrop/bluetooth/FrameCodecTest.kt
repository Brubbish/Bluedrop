package com.bluedrop.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

class FrameCodecTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02x".format(it) }

    @Test
    fun `PING frame matches the spec vector`() {
        val payload = byteArrayOf(0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77)
        val frame = FrameCodec.encode(Bdip.TYPE_PING, payload)
        assertEquals(
            "42 44 49 50 01 02 08 00 00 00 00 11 22 33 44 55 66 77",
            hex(frame),
        )
    }

    @Test
    fun `CLIPBOARD_TEXT hi matches the spec vector`() {
        val frame = FrameCodec.encode(Bdip.TYPE_CLIPBOARD_TEXT, "hi".toByteArray())
        assertEquals("42 44 49 50 01 10 02 00 00 00 68 69", hex(frame))
    }

    @Test
    fun `BYE shutdown matches the spec vector`() {
        val payload = byteArrayOf(0) + """{"message":"quit"}""".toByteArray()
        val frame = FrameCodec.encode(Bdip.TYPE_BYE, payload)
        assertEquals(
            "42 44 49 50 01 04 13 00 00 00 00 7b 22 6d 65 73 73 61 67 65 22 3a 22 71 75 69 74 22 7d",
            hex(frame),
        )
    }

    @Test
    fun `decode round-trips large payloads`() {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        val encoded = FrameCodec.encode(Bdip.TYPE_CLIPBOARD_IMAGE, payload)
        val decoded = FrameCodec.read(ByteArrayInputStream(encoded))
        assertEquals(Bdip.TYPE_CLIPBOARD_IMAGE, decoded.type)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `decode reads consecutive frames from one stream`() {
        val stream = ByteArrayInputStream(
            FrameCodec.encode(Bdip.TYPE_PING, ByteArray(8)) +
                FrameCodec.encode(Bdip.TYPE_CLIPBOARD_TEXT, "second".toByteArray())
        )
        val first = FrameCodec.read(stream)
        val second = FrameCodec.read(stream)
        assertEquals(Bdip.TYPE_PING, first.type)
        assertEquals(Bdip.TYPE_CLIPBOARD_TEXT, second.type)
        assertEquals("second", String(second.payload))
    }

    @Test
    fun `rejects bad magic`() {
        val bad = FrameCodec.encode(Bdip.TYPE_PING, ByteArray(0))
        bad[3] = 0x51.toByte() // "Q" instead of "P"
        assertThrows(IOException::class.java) {
            FrameCodec.read(ByteArrayInputStream(bad))
        }
    }

    @Test
    fun `rejects unknown version`() {
        val bad = FrameCodec.encode(Bdip.TYPE_PING, ByteArray(0))
        bad[4] = 0x02.toByte()
        assertThrows(IOException::class.java) {
            FrameCodec.read(ByteArrayInputStream(bad))
        }
    }

    @Test
    fun `rejects over-cap length`() {
        val header = byteArrayOf(
            0x42, 0x44, 0x49, 0x50, 0x01, Bdip.TYPE_CLIPBOARD_IMAGE.toByte(),
            // length = 9 MiB + 1
            0x01, 0x00, 0x90.toByte(), 0x00,
        )
        assertThrows(IOException::class.java) {
            FrameCodec.read(ByteArrayInputStream(header))
        }
    }

    @Test
    fun `truncated frame raises EOF`() {
        val full = FrameCodec.encode(Bdip.TYPE_CLIPBOARD_TEXT, "hello".toByteArray())
        val truncated: InputStream = ByteArrayInputStream(full.copyOf(full.size - 2))
        assertThrows(EOFException::class.java) {
            FrameCodec.read(truncated)
        }
    }

    @Test
    fun `empty payload frame is valid`() {
        val frame = FrameCodec.read(
            ByteArrayInputStream(FrameCodec.encode(Bdip.TYPE_PONG, ByteArray(0)))
        )
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun `u32 helper is little-endian`() {
        assertArrayEquals(byteArrayOf(0x39, 0x30, 0x00, 0x00), FrameCodec.u32(12_345))
    }
}
