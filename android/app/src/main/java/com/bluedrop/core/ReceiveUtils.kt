package com.bluedrop.core

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Persists payloads received over BDIP and exposes them as shareable URIs.
 *
 * Images go to the cache dir + FileProvider (for clipboard use); files go to
 * the shared Downloads collection via MediaStore so any app can open them.
 */
private const val IMAGE_DIR = "bluedrop_images"

fun saveReceivedImage(png: ByteArray, context: Context): Uri {
    val dir = File(context.cacheDir, IMAGE_DIR)
    if (!dir.exists()) dir.mkdirs()
    // clear previous image: the clipboard only ever points at the newest one
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "clip_${System.currentTimeMillis()}.png")
    file.writeBytes(png)
    val authority = "${context.packageName}.fileProvider"
    return FileProvider.getUriForFile(context, authority, file)
}

fun copyImageToClipboard(uri: Uri, context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = ClipData.newUri(context.contentResolver, "Bluedrop image", uri)
    clipboard.setPrimaryClip(clip)
}

/**
 * Publishes a fully-received temp file into MediaStore Downloads and returns
 * its content URI. The caller deletes the temp file afterwards.
 */
fun saveReceivedFile(temp: File, displayName: String, mime: String, context: Context): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
        put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
        put(MediaStore.Downloads.SIZE, temp.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val collection =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI
        else MediaStore.Files.getContentUri("external")
    val uri = resolver.insert(collection, values)
        ?: throw IOException("MediaStore insert failed")
    try {
        resolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
            ?: throw IOException("openOutputStream failed")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
    return uri
}
