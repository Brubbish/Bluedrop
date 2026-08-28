package com.bluedrop.bluetooth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Framing tests. The canonical vectors live in `/protocol/vectors.json` at the
 * monorepo root and are shared with the Windows test suite — both framers must
 * agree on exactly these bytes.
 */
class FrameCodecTest {

    companion object {
        private val vectors by lazy {
            val text = FrameCodecTest::class.java.getResourceAsStream("/vectors.json")
                ?.readBytes()?.decodeToString()
                ?: error("vectors.json not on test classpath")
            Json.parseToJsonElement(text).jsonObject
        }

        private fun hexToBytes(hex: String): ByteArray =
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        private fun firstFrameBytes(): ByteArray =
            hexToBytes(vectors["frames"]!!.jsonArray[0].jsonObject["frameHex"]!!.jsonPrimitive.content)
    }

    @Test
    fun `shared frame vectors round-trip`() {
        for (case in vectors["frames"]!!.jsonArray) {
            val obj = case.jsonObject
            val name = obj["name"]!!.jsonPrimitive.content
            val type = obj["type"]!!.jsonPrimitive.content.removePrefix("0x").toInt(16)
            val payload = hexToBytes(obj["payloadHex"]!!.jsonPrimitive.content)
            val wire = hexToBytes(obj["frameHex"]!!.jsonPrimitive.content)

            assertArrayEquals("vector $name: encoded bytes", wire, FrameCodec.encode(type, payload))

            val decoded = FrameCodec.read(ByteArrayInputStream(wire))
            assertEquals("vector $name: type", type, decoded.type)
            assertArrayEquals("vector $name: payload", payload, decoded.payload)
        }
    }

    @Test
    fun `shared reject vectors are refused`() {
        for (case in vectors["rejects"]!!.jsonArray) {
            val name = case.jsonObject["name"]!!.jsonPrimitive.content
            val bytes = hexToBytes(case.jsonObject["hex"]!!.jsonPrimitive.content)
            assertThrows("vector $name must be rejected", IOException::class.java) {
                FrameCodec.read(ByteArrayInputStream(bytes))
            }
        }
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
    fun `truncated vector frame raises EOF`() {
        val full = firstFrameBytes()
        val truncated: InputStream = ByteArrayInputStream(full.copyOf(full.size - 2))
        assertThrows(EOFException::class.java) {
            FrameCodec.read(truncated)
        }
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
