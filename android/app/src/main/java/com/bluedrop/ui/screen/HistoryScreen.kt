package com.bluedrop.ui.screen

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bluedrop.core.TransferHistory
import com.bluedrop.core.TransferRecord
import com.bluedrop.ui.navigation.safePopBackStack
import java.text.DateFormat
import java.util.Date

/**
 * Transfer history: every completed file/image transfer, newest first.
 * Per entry: open (tap), remove from list, or delete the stored file as well.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(navController: NavHostController) {
    val context = LocalContext.current
    val records by TransferHistory.records.collectAsState()
    var pendingFileDelete by remember { mutableStateOf<TransferRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors()
                    .copy(containerColor = colorScheme.primaryContainer),
                title = {
                    Text(
                        "Transfer history",
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onPrimaryContainer
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.safePopBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colorScheme.onPrimaryContainer
                        )
                    }
                },
                actions = {
                    if (records.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Clear list",
                                tint = colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (records.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "No transfers yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurfaceVariant
                )
                Text(
                    "Files and images you send or receive will appear here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(records, key = { it.id + it.timestamp }) { record ->
                    TransferRow(
                        record = record,
                        onOpen = {
                            record.uri?.let { uri ->
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    )
                                }
                            }
                        },
                        onRemove = { TransferHistory.remove(record.id) },
                        onDeleteFile = { pendingFileDelete = record },
                    )
                }
            }
        }
    }

    pendingFileDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingFileDelete = null },
            title = { Text("Delete file?") },
            text = {
                Text(
                    "\"${record.name}\" will be deleted from this device and removed " +
                        "from the history. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    TransferHistory.removeWithFile(context, record.id)
                    pendingFileDelete = null
                }) { Text("Delete", color = colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingFileDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All entries will be removed. Stored files are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    TransferHistory.clear()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TransferRow(
    record: TransferRecord,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onDeleteFile: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (record.direction == "sent") Icons.AutoMirrored.Filled.Send
                else Icons.Default.Download,
                contentDescription = record.direction,
                tint = if (record.direction == "sent") colorScheme.primary else Color(0xFF2E7D32),
                modifier = Modifier.size(22.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (record.kind) {
                            "image" -> Icons.Default.Image
                            "text" -> Icons.AutoMirrored.Filled.Chat
                            else -> Icons.Default.InsertDriveFile
                        },
                        contentDescription = record.kind,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        record.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 5.dp)
                    )
                    if (record.status == "failed") {
                        Text(
                            "· failed",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.error,
                        )
                    }
                }
                Text(
                    "${humanSize(record.size)} · " +
                        DateFormat.getDateTimeInstance().format(Date(record.timestamp)),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant
                )
                if (record.status == "failed" && record.error != null) {
                    Text(
                        record.error,
                        fontSize = 12.sp,
                        color = colorScheme.error
                    )
                }
            }
            if (record.uri != null) {
                IconButton(onClick = onDeleteFile) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete file and remove",
                        tint = colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove from list",
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
