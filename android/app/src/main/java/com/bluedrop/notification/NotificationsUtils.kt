package com.bluedrop.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bluedrop.R
import com.bluedrop.activities.MainActivity
import com.bluedrop.activities.ShareClipboardActivity
import com.bluedrop.bluetooth.BluetoothService

const val channelId = "BluedropServiceChannel"

fun createServiceNotification(context: Context): Notification {
    val contentIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val contentPendingIntent = PendingIntent.getActivity(
        context,
        0,
        contentIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val shareIntent = Intent(context, ShareClipboardActivity::class.java).apply {
        action = "ACTION_SHARE"
    }

    val sharePendingIntent = PendingIntent.getActivity(
        context, 1, shareIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val dismissIntent = Intent(context, NotificationReceiver::class.java).apply {
        action = "ACTION_DISMISS"
    }

    val dismissPendingIntent = PendingIntent.getBroadcast(
        context,
        2,
        dismissIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    return NotificationCompat.Builder(context, channelId)
        .setContentTitle("Bluedrop Active")
        .setContentText("Ready to share clipboard")
        .setSmallIcon(R.mipmap.ic_launcher)
        .addAction(0, "Share", sharePendingIntent)
        .addAction(0, "Dismiss", dismissPendingIntent)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(contentPendingIntent)
        .build()
}

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = "Bluedrop Service"
        val descriptionText = "Bluetooth clipboard sharing service"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(channelId, name, importance).apply {
            description = descriptionText
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }
}

fun showReceivedNotification(text: String, context: Context) {
    val notificationId = Math.random() * 10
    val copyIntent = Intent(context, NotificationReceiver::class.java).apply {
        action = "ACTION_COPY"
        putExtra("CLIP_TEXT", text)
        putExtra("NOTIFICATION_ID", notificationId.toInt())
    }

    val copyPendingIntent = PendingIntent.getBroadcast(
        context, 1, copyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification =
        NotificationCompat.Builder(context, channelId).setContentTitle("ClipText Received")
            .setContentText(text.take(50) + if (text.length > 50) "..." else "")
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(0, "Copy", copyPendingIntent)
            .setAutoCancel(true)
            .build()

    NotificationManagerCompat.from(context).apply {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notify(notificationId.toInt(), notification)
        }
    }
}

/** Notification for a received image/file, tapping it opens the content URI. */
fun showFileReceivedNotification(name: String, uri: android.net.Uri, context: Context) {
    val openIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val openPendingIntent = PendingIntent.getActivity(
        context, 3, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, channelId)
        .setContentTitle("Bluedrop received a file")
        .setContentText(name.take(60) + if (name.length > 60) "..." else "")
        .setSmallIcon(R.mipmap.ic_launcher)
        .setAutoCancel(true)
        .setContentIntent(openPendingIntent)
        .build()
    NotificationManagerCompat.from(context).apply {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notify((System.currentTimeMillis() and 0xFFFF).toInt(), notification)
        }
    }
}

fun sharingResultNotification(title: String, text: String, context: Context) {
    val notificationId = 1000
    val contentIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val contentPendingIntent = PendingIntent.getActivity(
        context,
        0,
        contentIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification =
        NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text.take(50) + if (text.length > 50) "..." else "")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

    NotificationManagerCompat.from(context).apply {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notify(notificationId, notification)
        }
    }
}

/** Ongoing progress notification for transfers above 1 MiB (id fixed so it updates in place). */
private const val TRANSFER_PROGRESS_NOTIFICATION_ID = 2000

fun showTransferProgressNotification(context: Context, title: String, received: Long, total: Long) {
    if (total <= 0) return
    val percent = ((received * 100) / total).toInt().coerceIn(0, 100)

    val cancelIntent = Intent(context, NotificationReceiver::class.java).apply {
        action = "ACTION_CANCEL_TRANSFER"
    }
    val cancelPendingIntent = PendingIntent.getBroadcast(
        context, 5, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, channelId)
        .setContentTitle(title)
        .setContentText("$percent% — " + humanSize(received) + " / " + humanSize(total))
        .setProgress(100, percent, false)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(0, "Cancel", cancelPendingIntent)
        .build()
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        == PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat.from(context).notify(TRANSFER_PROGRESS_NOTIFICATION_ID, notification)
    }
}

fun cancelTransferProgressNotification(context: Context) {
    NotificationManagerCompat.from(context).cancel(TRANSFER_PROGRESS_NOTIFICATION_ID)
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
