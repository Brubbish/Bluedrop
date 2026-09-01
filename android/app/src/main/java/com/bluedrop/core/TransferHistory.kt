package com.bluedrop.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Transfer history shown in the app's History screen. Records file/image/text
 * transfers in both directions, including failed/cancelled ones ([status] =
 * "failed"). One JSON file in app-private storage, capped at [MAX_RECORDS].
 */
@Serializable
data class TransferRecord(
    val id: String,
    val timestamp: Long,
    val direction: String, // "sent" | "received"
    val kind: String,      // "file" | "image" | "text"
    val name: String,
    val size: Long,
    val uri: String? = null, // content:// of the stored payload; null for sent/text items
    val mime: String = "application/octet-stream",
    val status: String = "ok", // "ok" | "failed"; default keeps old JSON files readable
    val error: String? = null,
)

object TransferHistory {
    private const val MAX_RECORDS = 500
    private const val FILE_NAME = "transfer_history.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _records = MutableStateFlow<List<TransferRecord>>(emptyList())
    val records: StateFlow<List<TransferRecord>> = _records.asStateFlow()

    private var storeFile: File? = null
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            storeFile = File(context.filesDir, FILE_NAME)
            _records.value = runCatching {
                json.decodeFromString<List<TransferRecord>>(storeFile!!.readText())
            }.getOrDefault(emptyList())
        }
    }

    fun add(record: TransferRecord) {
        synchronized(lock) {
            _records.value = (listOf(record) + _records.value).take(MAX_RECORDS)
            persistLocked()
        }
    }

    /** Removes the record from the list, keeping any stored payload. */
    fun remove(id: String) {
        synchronized(lock) {
            _records.value = _records.value.filterNot { it.id == id }
            persistLocked()
        }
    }

    /**
     * Removes the record and deletes its stored payload (received files only).
     * Returns false when the payload could not be deleted; the record is
     * removed regardless.
     */
    fun removeWithFile(context: Context, id: String): Boolean {
        val record = synchronized(lock) { _records.value.firstOrNull { it.id == id } }
        var fileDeleted = false
        if (record?.uri != null) {
            fileDeleted = runCatching {
                context.contentResolver.delete(Uri.parse(record.uri), null, null) > 0
            }.getOrDefault(false)
        }
        remove(id)
        return fileDeleted
    }

    fun clear() {
        synchronized(lock) {
            _records.value = emptyList()
            persistLocked()
        }
    }

    private fun persistLocked() {
        val file = storeFile ?: return
        runCatching {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(_records.value))
            if (!tmp.renameTo(file)) {
                file.writeText(json.encodeToString(_records.value))
                tmp.delete()
            }
        }
    }
}
