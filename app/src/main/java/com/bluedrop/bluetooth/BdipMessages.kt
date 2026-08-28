package com.bluedrop.bluetooth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON payload types carried inside BDIP frames (docs/PROTOCOL.md §3). */
object BdipJson {
    val lenient = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

@Serializable
data class HelloInfo(
    @SerialName("name") val name: String,
    @SerialName("proto") val proto: Int = Bdip.VERSION,
    @SerialName("caps") val caps: List<String> = listOf("text"),
    @SerialName("token") val token: String = "",
) {
    fun encode(): ByteArray = BdipJson.lenient.encodeToString(serializer(), this).toByteArray()

    companion object {
        fun decode(bytes: ByteArray): HelloInfo =
            BdipJson.lenient.decodeFromString(serializer(), String(bytes, Charsets.UTF_8))
    }
}

@Serializable
data class FileMeta(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("size") val size: Long,
    @SerialName("mime") val mime: String = "application/octet-stream",
    @SerialName("chunkSize") val chunkSize: Int = Bdip.CHUNK_SIZE,
    @SerialName("chunks") val chunks: Int,
) {
    fun encode(): ByteArray = BdipJson.lenient.encodeToString(serializer(), this).toByteArray()

    companion object {
        fun decode(bytes: ByteArray): FileMeta =
            BdipJson.lenient.decodeFromString(serializer(), String(bytes, Charsets.UTF_8))
    }
}

@Serializable
data class FileAck(
    @SerialName("id") val id: String,
    @SerialName("received") val received: Long = 0,
    @SerialName("done") val done: Boolean? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("path") val path: String? = null,
) {
    fun encode(): ByteArray = BdipJson.lenient.encodeToString(serializer(), this).toByteArray()

    companion object {
        fun decode(bytes: ByteArray): FileAck =
            BdipJson.lenient.decodeFromString(serializer(), String(bytes, Charsets.UTF_8))
    }
}
