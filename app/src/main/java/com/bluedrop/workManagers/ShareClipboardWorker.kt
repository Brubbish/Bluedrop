package com.bluedrop.workManagers

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.bluedrop.bluetooth.Bdip
import com.bluedrop.bluetooth.BluetoothService
import com.bluedrop.bluetooth.BluetoothServiceConnection
import com.bluedrop.bluetooth.SharingResult
import com.bluedrop.core.getSharingResultMessage
import com.bluedrop.core.tag
import com.bluedrop.notification.sharingResultNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class ShareClipboardWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    lateinit var bluetoothServiceConnection: BluetoothServiceConnection

    override suspend fun doWork(): Result {
        val clipText = inputData.getString(KEY_CLIP_TEXT)
        val uriString = inputData.getString(KEY_URI)
        val mime = inputData.getString(KEY_MIME)
        if (clipText.isNullOrEmpty() && uriString.isNullOrEmpty()) {
            Log.e(tag, "ShareClipboardWorker: No content provided.")
            sharingResultNotification("Sharing Failed", "Clipboard is empty", applicationContext)
            return Result.failure()
        }

        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        return suspendCancellableCoroutine { continuation ->
            bluetoothServiceConnection = BluetoothServiceConnection(
                onServiceConnected = { service ->
                    serviceScope.launch {
                        try {
                            val result = when {
                                uriString != null -> shareUri(service, uriString, mime)
                                !clipText.isNullOrEmpty() -> service.shareClipboard(clipText)
                                else -> {
                                    continuation.resume(Result.failure())
                                    return@launch
                                }
                            }
                            if (result != SharingResult.SUCCESS) {
                                val message = getSharingResultMessage(result)
                                sharingResultNotification(
                                    "Sharing Failed",
                                    message,
                                    applicationContext
                                )
                            }
                            Log.d(tag, "ShareClipboardWorker: shared successfully.")
                            continuation.resume(Result.success())
                        } catch (e: Exception) {
                            Log.e(tag, "ShareClipboardWorker: Error sharing: ${e.message}")
                            sharingResultNotification(
                                "Sharing Failed",
                                "Make sure the receiving device is ready",
                                applicationContext
                            )
                            continuation.resume(Result.failure())
                        } finally {
                            applicationContext.unbindService(bluetoothServiceConnection)
                        }
                    }
                },
                onServiceDisconnected = {
                    Log.d(tag, "ShareClipboardWorker: BluetoothService disconnected.")
                }
            )

            val intent = Intent(applicationContext, BluetoothService::class.java)
            val serviceBound = applicationContext.bindService(
                intent,
                bluetoothServiceConnection,
                Context.BIND_AUTO_CREATE
            )

            if (!serviceBound) {
                Log.e(tag, "ShareClipboardWorker: Failed to bind to BluetoothService.")
                sharingResultNotification(
                    "Sharing Failed",
                    "Make bluetooth is turned on",
                    applicationContext
                )
                continuation.resume(Result.failure())
                return@suspendCancellableCoroutine
            }
        }
    }

    /**
     * Routes a content URI to the right BDIP message: small images go as
     * CLIPBOARD_IMAGE (re-encoded to PNG per spec §3.3); everything else is
     * a chunked file transfer.
     */
    private suspend fun shareUri(
        service: BluetoothService,
        uriString: String,
        mime: String?,
    ): SharingResult {
        val uri = Uri.parse(uriString)
        val resolver = applicationContext.contentResolver
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size <= 0) return SharingResult.SENDING_ERROR

        val resolvedMime = mime?.takeIf { it != "*" } ?: resolver.getType(uri) ?: "*/*"
        return if (resolvedMime.startsWith("image/") && size <= Bdip.MAX_IMAGE) {
            val png = resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (resolvedMime == "image/png" && isPng(bytes)) {
                    bytes
                } else {
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return SharingResult.SENDING_ERROR
                    val out = ByteArrayOutputStream()
                    decoded.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            } ?: return SharingResult.SENDING_ERROR
            service.shareClipboardImage(png)
        } else {
            val displayName = queryDisplayName(uri) ?: uri.lastPathSegment
                ?.substringAfterLast('/') ?: "file"
            service.shareFile(
                displayName = displayName,
                size = size,
                mime = resolvedMime,
                openStream = { resolver.openInputStream(uri)!! },
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        resolverDisplayName(uri)
    } catch (e: Exception) {
        null
    }

    private fun resolverDisplayName(uri: Uri): String? =
        applicationContext.contentResolver.query(
            uri, null, null, null, null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()

    companion object {
        const val KEY_CLIP_TEXT = "clip_text"
        const val KEY_URI = "uri"
        const val KEY_MIME = "mime"
    }
}
