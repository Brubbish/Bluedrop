package com.bluedrop.activities

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluedrop.bluetooth.BluetoothService
import com.bluedrop.core.Essentials
import com.bluedrop.core.SettingsPreferences
import com.bluedrop.core.showToast
import com.bluedrop.ui.navigation.Navigation
import com.bluedrop.ui.theme.BluedropTheme
import com.bluedrop.ui.viewModel.SettingsViewModel

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var pairedDevices by mutableStateOf<Set<BluetoothDevice>>(emptySet())

    private var discoveredDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    private var isScanning by mutableStateOf(false)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        registerBluetoothReceiver()

        setContent {
            val settingsPrefs = SettingsPreferences(this)
            val settingsViewModel = viewModel { SettingsViewModel(settingsPrefs) }
            val autoCopyEnabled = settingsViewModel.autoCopy.collectAsStateWithLifecycle().value
            val darkTheme = settingsViewModel.isDarkMode.collectAsStateWithLifecycle().value

            Essentials.updateAutoCopy(autoCopyEnabled)

            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !darkTheme

            BluedropTheme(darkTheme = darkTheme) {
                Navigation(
                    startBluetoothService = { startBluetoothService() },
                    pairedDevices = pairedDevices,
                    refreshPairedDevices = { getPairedDevicesList() },
                    stopBluetoothService = { stopBluetoothService() },
                    settingsViewModel = settingsViewModel,
                    discoveredDevices = discoveredDevices,
                    isScanning = isScanning,
                    bluetoothEnabled = bluetoothAdapter.isEnabled,
                    onStartScan = { startBluetoothScan() },
                    onPairDevice = { device -> pairBluetoothDevice(device) },
                )
            }
        }
        checkPermissions()
        maybeShowBatteryGuidance()
    }

    /**
     * Phase 4: one-time guidance. The persistent BDIP link dies under Doze
     * unless the app is exempt from battery optimization.
     */
    private fun maybeShowBatteryGuidance() {
        val prefs = SettingsPreferences(this)
        if (prefs.isBatteryGuidanceShown()) return
        prefs.markBatteryGuidanceShown()

        val powerManager = getSystemService(android.os.PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        android.app.AlertDialog.Builder(this)
            .setTitle("Keep the Bluedrop link alive")
            .setMessage(
                "Bluedrop keeps a persistent Bluetooth connection so your " +
                    "clipboard and files transfer instantly. Android's battery " +
                    "optimization will put that link to sleep in the " +
                    "background.\n\nExempt Bluedrop from battery optimization?"
            )
            .setPositiveButton("Exempt") { _, _ ->
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName"),
                    )
                )
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (!discoveredDevices.contains(it)) {
                            discoveredDevices = discoveredDevices + it
                        }
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isScanning = false
                }
            }
        }
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothReceiver, filter)
    }

    private fun unregisterBluetoothReceiver() {
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {
            // Receiver not registered
        }
    }

    private fun startBluetoothScan() {
        if (!bluetoothAdapter.isEnabled) {
            checkBluetoothEnabled()
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    Manifest.permission.BLUETOOTH_SCAN
                else
                    Manifest.permission.BLUETOOTH_ADMIN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkPermissions()
            return
        }

        discoveredDevices = emptyList()
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
        bluetoothAdapter.startDiscovery()
        isScanning = true
    }

    private fun pairBluetoothDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(
                this,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    Manifest.permission.BLUETOOTH_CONNECT
                else
                    Manifest.permission.BLUETOOTH
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkPermissions()
            return
        }

        val success = device.createBond()
        if (success) {
            showToast("Pairing with ${device.name ?: "device"}...", this)
        } else {
            showToast("Failed to pair with ${device.name ?: "device"}", this)
        }
    }

    private val requestEnableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            getPairedDevicesList()
        } else {
            showToast("Bluetooth is required to find devices.", this)
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBluetoothEnabled()
        } else {
            showToast("Needed permissions denied.", this)
        }
    }

    @SuppressLint("InlinedApi")
    private fun checkPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missingPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions)
        } else {
            checkBluetoothEnabled()
        }
    }

    private fun checkBluetoothEnabled() {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestEnableBluetooth.launch(enableBtIntent)
        } else {
            getPairedDevicesList()
        }
    }

    /** Wrapper around loadPairedDevices for MainScreen composable*/
    fun getPairedDevicesList() {
        pairedDevices = loadPairedDevices()
    }

    private fun loadPairedDevices(): Set<BluetoothDevice> {
        return if (ActivityCompat.checkSelfPermission(
                this,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    Manifest.permission.BLUETOOTH_CONNECT
                else
                    Manifest.permission.BLUETOOTH
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            emptySet()
        } else {
            bluetoothAdapter.bondedDevices ?: emptySet()
        }
    }

    private fun startBluetoothService() {
        checkPermissions()
        val serviceIntent = Intent(this, BluetoothService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopBluetoothService() {
        val serviceIntent = Intent(this, BluetoothService::class.java)
        stopService(serviceIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterBluetoothReceiver()
        if (bluetoothAdapter.isDiscovering) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        Manifest.permission.BLUETOOTH_SCAN
                    else
                        Manifest.permission.BLUETOOTH_ADMIN
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothAdapter.cancelDiscovery()
            }
        }
    }
}