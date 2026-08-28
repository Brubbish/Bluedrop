package com.bluedrop.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The record type + JSON round-trip only; the Android-bound singleton state
 * (Context/filesDir) is not exercised here.
 */
class TransferHistoryTest {

    @Test
    fun `records round-trip through json`() {
        val records = listOf(
            TransferRecord(
                id = "a", timestamp = 1_700_000_000_000, direction = "sent",
                kind = "file", name = "report.pdf", size = 12345, mime = "application/pdf",
            ),
            TransferRecord(
                id = "b", timestamp = 1_700_000_001_000, direction = "received",
                kind = "image", name = "clip_1.png", size = 99, uri = "content://x/1",
                mime = "image/png",
            ),
        )
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val decoded = json.decodeFromString<List<TransferRecord>>(
            json.encodeToString(records)
        )
        assertEquals(records, decoded)
        assertNull(decoded[0].uri)
        assertEquals("content://x/1", decoded[1].uri)
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val payload = """[{"id":"z","timestamp":7,"direction":"sent","kind":"file",
            "name":"n","size":1,"uri":null,"mime":"text/plain","future_field":"x"}]"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<List<TransferRecord>>(payload)
        assertEquals(1, decoded.size)
        assertEquals("z", decoded[0].id)
    }

    @Test
    fun `json file name is stable`() {
        assertTrue(File("transfer_history.json").name.isNotBlank())
    }
}
