package com.bluedrop.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bluedrop.core.getClipText
import com.bluedrop.core.tag
import com.bluedrop.workManagers.ShareClipboardWorker

/**
 * Entry point for pushes: the in-app/notification "share clipboard" action
 * (reads the local clipboard) and the system share sheet (text, images, and
 * files arrive as ACTION_SEND with EXTRA_STREAM).
 */
class ShareClipboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            "ACTION_SHARE" -> handleShareClipboard()

            Intent.ACTION_SEND -> handleShareSend(intent)

            Intent.ACTION_SEND_MULTIPLE -> handleShareSendMultiple(intent)

            else -> finish()
        }
    }

    private fun handleShareClipboard() {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val clipText = getClipText(this)
                Log.d(tag, "ShareClipboardActivity: Clipboard text: $clipText")
                val inputData = Data.Builder()
                    .putString(ShareClipboardWorker.KEY_CLIP_TEXT, clipText)
                    .build()
                enqueue(inputData)
            } catch (e: Exception) {
                Log.e(tag, "ShareClipboardActivity: Failed to get clip text. Reason: ${e.message}")
            } finally {
                finish()
            }
        }, 300)
    }

    private fun handleShareSend(intent: Intent) {
        try {
            @Suppress("DEPRECATION")
            val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            val text: String? = intent.getStringExtra(Intent.EXTRA_TEXT)
            when {
                stream != null -> enqueueShareUri(stream, intent.type ?: "*/*")

                !text.isNullOrEmpty() -> {
                    val inputData = Data.Builder()
                        .putString(ShareClipboardWorker.KEY_CLIP_TEXT, text)
                        .build()
                    enqueue(inputData)
                }

                else -> {
                    // fall back to whatever text the clipboard holds
                    val inputData = Data.Builder()
                        .putString(ShareClipboardWorker.KEY_CLIP_TEXT, getClipText(this))
                        .build()
                    enqueue(inputData)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "share-sheet handling failed: ${e.message}")
        } finally {
            finish()
        }
    }

    private fun handleShareSendMultiple(intent: Intent) {
        try {
            val mime = intent.type ?: "*/*"
            @Suppress("DEPRECATION")
            val streams: List<Uri> =
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: emptyList()
            streams.forEach { enqueueShareUri(it, mime) }
        } catch (e: Exception) {
            Log.e(tag, "multi-share handling failed: ${e.message}")
        } finally {
            finish()
        }
    }

    private fun enqueueShareUri(uri: Uri, mime: String) {
        val inputData = Data.Builder()
            .putString(ShareClipboardWorker.KEY_URI, uri.toString())
            .putString(ShareClipboardWorker.KEY_MIME, mime)
            .build()
        enqueue(inputData)
    }

    private fun enqueue(inputData: Data) {
        val shareWorkRequest = OneTimeWorkRequestBuilder<ShareClipboardWorker>()
            .setInputData(inputData)
            .build()
        WorkManager.getInstance(applicationContext).enqueue(shareWorkRequest)
    }
}
