package com.bluedrop

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.bluedrop.bluetooth.Bdip
import com.bluedrop.bluetooth.BluetoothService
import com.bluedrop.bluetooth.FrameCodec
import com.bluedrop.bluetooth.SharingResult
import com.bluedrop.core.Essentials
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID


/** Streams the given bytes once, then blocks like an idle socket instead of
 *  hitting EOF — a persistent BDIP session expects the latter. */
private class IdleAfter(private val data: ByteArray) : java.io.InputStream() {
    private var pos = 0
    @Volatile private var closed = false

    override fun read(): Int {
        while (true) {
            if (pos < data.size) return data[pos++].toInt() and 0xFF
            if (closed) return -1
            Thread.sleep(5)
        }
    }

    override fun close() {
        closed = true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
@ExperimentalCoroutinesApi
class BluetoothServiceTest {

    @Mock
    private lateinit var bluetoothManager: BluetoothManager

    @Mock
    private lateinit var bluetoothAdapter: BluetoothAdapter

    @Mock
    private lateinit var serverSocket: BluetoothServerSocket

    @Mock
    private lateinit var clientSocket: BluetoothSocket

    @Mock
    private lateinit var remoteDevice: BluetoothDevice

    @Mock
    private lateinit var notificationManager: NotificationManager

    @Mock
    private lateinit var clipboardManager: ClipboardManager

    private lateinit var controller: ServiceController<BluetoothService>
    private lateinit var service: BluetoothService
    private lateinit var context: Context

    private val testUuid: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    private val testDeviceAddress = "00:11:22:33:AA:BB"

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        context = ApplicationProvider.getApplicationContext()

        val shadowApplication = shadowOf(RuntimeEnvironment.getApplication())
        shadowApplication.setSystemService(Context.BLUETOOTH_SERVICE, bluetoothManager)
        shadowApplication.setSystemService(Context.NOTIFICATION_SERVICE, notificationManager)
        shadowApplication.setSystemService(Context.CLIPBOARD_SERVICE, clipboardManager)

        `when`(bluetoothManager.adapter).thenReturn(bluetoothAdapter)
        `when`(bluetoothAdapter.listenUsingRfcommWithServiceRecord(any(), any())).thenReturn(
            serverSocket
        )
        `when`(bluetoothAdapter.getRemoteDevice(testDeviceAddress)).thenReturn(remoteDevice)

        shadowApplication.grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        controller = Robolectric.buildService(BluetoothService::class.java)
        service = controller.create().get()

        TestScope().launch {
            Essentials.clear()
        }
    }

    @After
    fun tearDown() {
        controller.destroy()
        TestScope().launch {
            Essentials.clear()
        }
    }

    @Test
    fun `onStartCommand starts bluetooth server`() = runTest {
        val intent = Intent().putExtra("SELECTED_DEVICES", arrayOf(testDeviceAddress))
        controller.withIntent(intent).startCommand(0, 1)

        verify(bluetoothAdapter, timeout(2000)).listenUsingRfcommWithServiceRecord("Bluedrop", testUuid)
    }

    @Test
    fun `shareClipboard returns NO_SELECTED_DEVICES when addresses are empty`() = runTest {
        Essentials.updateSelectedDevices(setOf())

        val result = service.shareClipboard("test")
        Assert.assertEquals(SharingResult.NO_SELECTED_DEVICES, result)
    }

    @Test
    fun `shareClipboard returns PERMISSION_NOT_GRANTED when permission is missing`() = runTest {
        val shadowApplication = ShadowApplication()
        shadowApplication.denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        Essentials.updateSelectedDevices(setOf(testDeviceAddress))

        val result = service.shareClipboard("test clipboard")
        Assert.assertEquals(SharingResult.PERMISSION_NOT_GRANTED, result)
    }

    @Test
    fun `shareClipboard returns SUCCESS on successful send`() = runTest {
        val outputStream = ByteArrayOutputStream()
        // the session performs a HELLO handshake before sending; feed a valid
        // responder HELLO back through the mocked socket input
        val peerHello = FrameCodec.encode(
            Bdip.TYPE_HELLO,
            """{"name":"peer","proto":1,"caps":["text"],"token":""}""".toByteArray(),
        )
        `when`(remoteDevice.createRfcommSocketToServiceRecord(testUuid)).thenReturn(clientSocket)
        `when`(clientSocket.outputStream).thenReturn(outputStream)
        `when`(clientSocket.inputStream).thenReturn(IdleAfter(peerHello))

        Essentials.updateSelectedDevices(setOf(testDeviceAddress))

        val result = service.shareClipboard("hello")

        Assert.assertEquals(SharingResult.SUCCESS, result)

        // first frame on the wire is our HELLO, then the clipboard text frame;
        // the writer coroutine flushes asynchronously, so wait for both frames
        kotlinx.coroutines.runBlocking {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && outputStream.size() < 80) {
                kotlinx.coroutines.delay(10)
            }
        }
        val allFrames = outputStream.toByteArray()
        val first = FrameCodec.read(ByteArrayInputStream(allFrames))
        Assert.assertEquals(Bdip.TYPE_HELLO, first.type)
        val second = FrameCodec.read(ByteArrayInputStream(allFrames.copyOfRange(
            Bdip.HEADER_SIZE + first.payload.size, allFrames.size
        )))
        Assert.assertEquals(Bdip.TYPE_CLIPBOARD_TEXT, second.type)
        Assert.assertEquals("hello", String(second.payload, Charsets.UTF_8))

        verify(clientSocket).connect()
    }

    @Test
    fun `shareClipboard returns SENDING_ERROR on connection failure`() = runTest {
        `when`(remoteDevice.createRfcommSocketToServiceRecord(testUuid)).thenReturn(clientSocket)
        `when`(clientSocket.connect()).thenThrow(IOException("Connection failed!"))

        Essentials.updateSelectedDevices(setOf(testDeviceAddress))

        val result = service.shareClipboard("test")
        Assert.assertEquals(SharingResult.SENDING_ERROR, result)
    }

    @Test
    fun `server handles incoming connection and parses data`() = runTest {
        val testMessage = "{\"clip\":\"test clip data from another device\"}\n"
        val inputStream = ByteArrayInputStream(testMessage.toByteArray())
        val incomingSocket: BluetoothSocket = mock {
            on { this.inputStream } doReturn inputStream
        }

        `when`(serverSocket.accept())
            .thenReturn(incomingSocket)
            .thenThrow(IOException("Server stopped"))

        controller.startCommand(0, 1)

        verify(incomingSocket, timeout(1000)).close()
    }

    @Test
    fun `updateSelectedDevices updates addresses correctly`() = runTest {
        val testAddresses = arrayOf("11:22:33:44:55:66", "AA:BB:CC:DD:EE:FF")

        Essentials.updateSelectedDevices(testAddresses.toSet())

        val mockDevice1 = mock<BluetoothDevice>()
        val mockDevice2 = mock<BluetoothDevice>()
        val mockSocket1 = mock<BluetoothSocket>()
        val mockSocket2 = mock<BluetoothSocket>()
        val outputStream1 = ByteArrayOutputStream()
        val outputStream2 = ByteArrayOutputStream()

        `when`(bluetoothAdapter.getRemoteDevice(testAddresses[0])).thenReturn(mockDevice1)
        `when`(bluetoothAdapter.getRemoteDevice(testAddresses[1])).thenReturn(mockDevice2)
        `when`(mockDevice1.createRfcommSocketToServiceRecord(testUuid)).thenReturn(mockSocket1)
        `when`(mockDevice2.createRfcommSocketToServiceRecord(testUuid)).thenReturn(mockSocket2)
        `when`(mockSocket1.outputStream).thenReturn(outputStream1)
        `when`(mockSocket2.outputStream).thenReturn(outputStream2)

        val result = service.shareClipboard("test")
        assert(result != SharingResult.NO_SELECTED_DEVICES)
    }

    @Test
    fun `service sets isServiceBound flag in onCreate`() = runTest {
        Essentials.clear()
        delay(1000)

        val newController = Robolectric.buildService(BluetoothService::class.java)
        newController.create()

        Assert.assertEquals(true, Essentials.isServiceRunning.value)

        newController.destroy()
    }

    @Test
    fun `service clears isServiceBound flag in onDestroy`() = runTest {
        controller.destroy()
        delay(1000)
        Assert.assertEquals(false, Essentials.isServiceRunning.value)
    }
}