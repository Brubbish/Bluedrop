package com.bluedrop.bluetooth

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.bluedrop.core.Essentials
import com.bluedrop.core.copyToClipboard
import com.bluedrop.core.copyImageToClipboard
import com.bluedrop.core.saveReceivedImage
import com.bluedrop.core.saveReceivedFile
import com.bluedrop.notification.createNotificationChannel
import com.bluedrop.notification.createServiceNotification
import com.bluedrop.notification.showReceivedNotification
import com.bluedrop.notification.showFileReceivedNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that owns all BDIP sessions (docs/PROTOCOL.md).
 *
 * Keeps an RFCOMM listener open (accepting both BDIP and legacy ClipSync
 * clients via 4-byte sniffing), maintains one persistent session per peer,
 * dials on demand with backoff when a share has no live session, and exposes
 * clipboard/file send entry points through the binder.
 */
class BluetoothService : Service() {

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val TAG = "BluetoothService"
        private val BD_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_CAP_MS = 30_000L
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "uncaught", e) }
    )
    private lateinit var bluetoothAdapter: BluetoothAdapter
    @Volatile private var selectedDeviceAddresses = setOf<String>()
    @Volatile private var autoCopyEnabled = true
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private val sessions = ConcurrentHashMap<String, BdipSession>()
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }

    /** Per-session callback target; keeps per-peer receive state isolated. */
    private inner class SessionHandler(private val session: BdipSession) : BdipSession.Listener {
        var incoming: IncomingTransfer? = null

        override fun onEstablished(peer: HelloInfo) {
            Log.i(TAG, "Session established with ${peer.name} (caps=${peer.caps})")
        }

        override fun onClipboardText(text: String) {
            cancelErrorNotification()
            if (autoCopyEnabled) copyToClipboard(text, this@BluetoothService)
            else showReceivedNotification(text, this@BluetoothService)
        }

        override fun onClipboardImage(png: ByteArray) {
            cancelErrorNotification()
            try {
                val uri = saveReceivedImage(png, this@BluetoothService)
                copyImageToClipboard(uri, this@BluetoothService)
                showFileReceivedNotification("Image received", uri, this@BluetoothService)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply received image", e)
            }
        }

        override fun onFileMeta(meta: FileMeta) {
            incoming = IncomingTransfer(meta)
        }

        override fun onFileChunk(index: Int, data: ByteArray) {
            val entry = incoming ?: return
            val meta = entry.meta
            if (index != entry.expectedIndex) {
                failTransfer("out-of-order chunk $index (expected ${entry.expectedIndex})")
                return
            }
            val out = entry.tempFile ?: File(cacheDir, "bluedrop-${meta.id}").also {
                entry.tempFile = it
            }
            FileOutputStream(out, true).use { it.write(data) }
            entry.received += data.size
            entry.expectedIndex++
            session.sendFileAck(FileAck(id = meta.id, received = entry.received))
            if (entry.received >= meta.size) finalizeTransfer()
        }

        override fun onFileAck(ack: FileAck) {
            // consumed by BdipSession.sendFile; progress UI can observe here
        }

        override fun onClosed(reason: String) {
            Log.i(TAG, "Session closed: $reason")
            incoming?.tempFile?.delete()
            incoming = null
            sessions.entries.removeIf { it.value === session }
            if (session.isEstablished) peerOf(session)?.let { ensureReconnectLoop(it) }
        }

        private fun finalizeTransfer() {
            val entry = incoming ?: return
            val meta = entry.meta
            val temp = entry.tempFile
            if (temp == null || temp.length() != meta.size) {
                failTransfer("size mismatch")
                return
            }
            try {
                val uri = saveReceivedFile(temp, meta.name, meta.mime, this@BluetoothService)
                temp.delete()
                incoming = null
                session.sendFileAck(
                    FileAck(id = meta.id, received = meta.size, done = true, path = uri.toString())
                )
                showFileReceivedNotification(meta.name, uri, this@BluetoothService)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to finalize ${meta.name}", e)
                failTransfer(e.message ?: "write failed")
            }
        }

        private fun failTransfer(message: String) {
            val meta = incoming?.meta ?: return
            Log.e(TAG, "Transfer of ${meta.name} failed: $message")
            incoming?.tempFile?.delete()
            incoming = null
            session.sendFileAck(FileAck(id = meta.id, error = message))
        }
    }

    /** Bookkeeping for a file being received on one session. */
    private class IncomingTransfer(val meta: FileMeta) {
        var received: Long = 0
        var expectedIndex = 0
        var tempFile: File? = null
    }

    private fun peerOf(session: BdipSession): String? =
        sessions.entries.firstOrNull { it.value === session }?.key

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch { Essentials.setServiceRunning(true) }
        serviceScope.launch {
            Essentials.selectedDevices.collectLatest { devices ->
                selectedDeviceAddresses = devices
                reconnectJobs.keys.filter { it !in devices }.forEach { address ->
                    reconnectJobs.remove(address)?.cancel()
                }
                devices.forEach { ensureReconnectLoop(it) }
            }
        }
        serviceScope.launch {
            Essentials.autoCopy.collect { isEnabled -> autoCopyEnabled = isEnabled }
        }
        createNotificationChannel(this)
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        startForeground(FOREGROUND_NOTIFICATION_ID, createServiceNotification(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startBluetoothServer()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopBluetoothServer()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- server (responder side)

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun startBluetoothServer() {
        if (acceptJob?.isActive == true) return
        acceptJob = serviceScope.launch {
            try {
                @Suppress("MissingPermission")
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("Bluedrop", BD_UUID)
                while (isActive) {
                    val socket = try {
                        serverSocket?.accept() ?: break
                    } catch (e: IOException) {
                        Log.e(TAG, "Server socket accept failed", e)
                        break
                    }
                    handleIncomingConnection(socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
            }
        }
    }

    private fun handleIncomingConnection(socket: BluetoothSocket) {
        serviceScope.launch {
            try {
                val buffered = BufferedInputStream(socket.inputStream, 16 * 1024)
                val peek = ByteArray(4)
                var n = 0
                while (n < 4) {
                    val r = buffered.read(peek, n, 4 - n)
                    if (r < 0) {
                        socket.close()
                        return@launch
                    }
                    n += r
                }
                val address = try {
                    @Suppress("MissingPermission")
                    socket.remoteDevice?.address ?: "unknown"
                } catch (e: SecurityException) {
                    socket.close()
                    return@launch
                } catch (e: Exception) {
                    "unknown"
                }
                if (peek.contentEquals(Bdip.MAGIC_BYTES)) {
                    startSession(socket, buffered, address, BdipSession.Role.RESPONDER)
                } else {
                    handleLegacyConnection(socket, buffered, peek)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error handling connection", e)
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    /** ClipSync v1.3 clients: one JSON line then close. Kept until v2.0.0. */
    private fun handleLegacyConnection(socket: BluetoothSocket, buffered: BufferedInputStream, peek: ByteArray) {
        try {
            val rest = BufferedReader(InputStreamReader(buffered)).readLine() ?: ""
            val message = String(peek, Charsets.US_ASCII) + rest
            Log.d(TAG, "Legacy JSON received (${message.length} chars)")
            val json = JSONObject(message)
            val clipText = json.getString("clip")
            if (autoCopyEnabled) copyToClipboard(clipText, this) else showReceivedNotification(clipText, this)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing legacy JSON", e)
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun stopBluetoothServer() {
        sessions.values.forEach { it.shutdown() }
        sessions.clear()
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        serviceScope.coroutineContext.cancelChildren()
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serviceScope.launch { Essentials.clear() }
    }

    // ---------------------------------------------------------------- session management

    private fun adapterName(): String = try {
        @Suppress("MissingPermission")
        bluetoothAdapter.name ?: "Android"
    } catch (e: SecurityException) {
        "Android"
    }

    private fun helloInfo(): HelloInfo = HelloInfo(
        name = adapterName(),
        caps = listOf("text", "image", "file"),
    )

    private fun startSession(
        socket: BluetoothSocket,
        input: InputStream,
        address: String,
        role: BdipSession.Role,
    ): BdipSession {
        val existing = sessions[address]
        if (existing != null && existing.isEstablished && role == BdipSession.Role.RESPONDER) {
            // extra socket while healthy: refuse per spec §1
            Log.w(TAG, "Refusing extra connection from $address")
            try {
                socket.close()
            } catch (_: IOException) {
            }
            return existing
        }
        existing?.shutdown("replaced")
        lateinit var session: BdipSession
        val handler = SessionHandlerHolder()
        session = BdipSession(
            serviceScope,
            input,
            socket.outputStream,
            { try { socket.close() } catch (_: IOException) {} },
            SessionHandlerDelegate { handler.instance },
            helloInfo(),
            role,
        )
        handler.instance = SessionHandler(session)
        sessions[address] = session
        session.start()
        return session
    }

    /** Lets the handler reference its session before construction completes. */
    private class SessionHandlerHolder {
        lateinit var instance: BdipSession.Listener
    }

    private class SessionHandlerDelegate(val get: () -> BdipSession.Listener) : BdipSession.Listener {
        override fun onEstablished(peer: HelloInfo) = get().onEstablished(peer)
        override fun onClipboardText(text: String) = get().onClipboardText(text)
        override fun onClipboardImage(png: ByteArray) = get().onClipboardImage(png)
        override fun onFileMeta(meta: FileMeta) = get().onFileMeta(meta)
        override fun onFileChunk(index: Int, data: ByteArray) = get().onFileChunk(index, data)
        override fun onFileAck(ack: FileAck) = get().onFileAck(ack)
        override fun onClosed(reason: String) = get().onClosed(reason)
    }

    /** Dials a peer, handshakes, and registers the session. */
    @Suppress("MissingPermission")
    private suspend fun dial(address: String): BdipSession {
        if (!hasConnectPermission()) throw SecurityException("BLUETOOTH_CONNECT not granted")
        val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(address)
        val socket = device.createRfcommSocketToServiceRecord(BD_UUID)
        withTimeout(CONNECT_TIMEOUT_MS) {
            socket.connect()
        }
        return startSession(socket, socket.inputStream, address, BdipSession.Role.INITIATOR)
    }

    private suspend fun sessionOrDial(address: String): BdipSession? {
        sessions[address]?.let { if (it.isEstablished) return it }
        return try {
            val session = dial(address)
            session.awaitEstablished()
            session
        } catch (e: Exception) {
            Log.e(TAG, "Dial to $address failed: ${e.message}")
            ensureReconnectLoop(address)
            null
        }
    }

    private fun ensureReconnectLoop(address: String) {
        if (reconnectJobs.containsKey(address)) return
        if (address !in selectedDeviceAddresses) return
        val job = serviceScope.launch {
            var backoff = RECONNECT_BASE_MS
            while (isActive && address in selectedDeviceAddresses) {
                if (sessions[address]?.isEstablished == true) break
                delay(backoff)
                if (sessions[address]?.isEstablished == true) break
                try {
                    val session = dial(address)
                    session.awaitEstablished()
                    backoff = RECONNECT_BASE_MS
                } catch (e: Exception) {
                    backoff = (backoff * 2).coerceAtMost(RECONNECT_CAP_MS)
                }
            }
        }
        reconnectJobs[address] = job
        job.invokeOnCompletion { reconnectJobs.remove(address, job) }
    }

    // ---------------------------------------------------------------- public send API (binder)

    suspend fun shareClipboard(text: String): SharingResult {
        cancelErrorNotification()
        val targets = Essentials.selectedDevices.value
        if (targets.isEmpty()) {
            return SharingResult.NO_SELECTED_DEVICES
        }
        if (!hasConnectPermission()) {
            return SharingResult.PERMISSION_NOT_GRANTED
        }
        var anySuccess = false
        targets.forEach { address ->
            val session = sessionOrDial(address) ?: return@forEach
            anySuccess = session.sendClipboardText(text) || anySuccess
        }
        return if (anySuccess) SharingResult.SUCCESS else SharingResult.SENDING_ERROR
    }

    /** Sends a PNG payload as CLIPBOARD_IMAGE to all selected devices. */
    suspend fun shareClipboardImage(png: ByteArray): SharingResult {
        val targets = Essentials.selectedDevices.value
        if (targets.isEmpty()) {
            return SharingResult.NO_SELECTED_DEVICES
        }
        var anySuccess = false
        targets.forEach { address ->
            val session = sessionOrDial(address) ?: return@forEach
            anySuccess = session.sendClipboardImage(png) || anySuccess
        }
        return if (anySuccess) SharingResult.SUCCESS else SharingResult.SENDING_ERROR
    }

    /**
     * Sends one file to all selected devices using FILE_META/chunks (§3.4).
     * [openStream] is invoked once per destination; the stream is closed here.
     */
    suspend fun shareFile(
        displayName: String,
        size: Long,
        mime: String,
        openStream: () -> InputStream,
    ): SharingResult {
        val targets = Essentials.selectedDevices.value
        if (targets.isEmpty()) {
            return SharingResult.NO_SELECTED_DEVICES
        }
        if (size <= 0 || size > Bdip.MAX_FILE) return SharingResult.SENDING_ERROR
        var anySuccess = false
        selectedDeviceAddresses.forEach { address ->
            val session = sessionOrDial(address) ?: return@forEach
            val stream = openStream()
            try {
                val meta = FileMeta(
                    id = UUID.randomUUID().toString(),
                    name = displayName,
                    size = size,
                    mime = mime,
                    chunks = ((size + Bdip.CHUNK_SIZE - 1) / Bdip.CHUNK_SIZE).toInt(),
                )
                val sent = session.sendFile(meta) { _, count -> stream.readNBytesCompat(count) }
                anySuccess = sent > 0 || anySuccess
            } finally {
                try {
                    stream.close()
                } catch (_: IOException) {
                }
            }
        }
        return if (anySuccess) SharingResult.SUCCESS else SharingResult.SENDING_ERROR
    }

    // ---------------------------------------------------------------- misc

    private fun cancelErrorNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(1000)
    }
}

/** InputStream.readNBytes is API 33+; provide a compatible full read. */
private fun InputStream.readNBytesCompat(count: Int): ByteArray {
    val buf = ByteArray(count)
    var off = 0
    while (off < count) {
        val n = read(buf, off, count - off)
        if (n < 0) throw IOException("EOF mid-chunk")
        off += n
    }
    return buf
}
