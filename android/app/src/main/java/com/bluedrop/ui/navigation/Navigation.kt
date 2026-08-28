package com.bluedrop.ui.navigation

import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bluedrop.ui.screen.BluetoothScannerScreen
import com.bluedrop.ui.screen.MainScreen
import com.bluedrop.ui.screen.SettingsScreen
import com.bluedrop.ui.screen.SupportScreen
import com.bluedrop.ui.viewModel.SettingsViewModel
import kotlinx.serialization.Serializable

@Composable
fun Navigation(
    startBluetoothService: () -> Unit,
    pairedDevices: Set<BluetoothDevice>,
    refreshPairedDevices: () -> Unit,
    stopBluetoothService: () -> Unit,
    settingsViewModel: SettingsViewModel,
    discoveredDevices: List<BluetoothDevice>,
    isScanning: Boolean,
    bluetoothEnabled: Boolean,
    onStartScan: () -> Unit,
    onPairDevice: (BluetoothDevice) -> Unit,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Screens.MainScreen
    ) {
        composable<Screens.MainScreen> {
            MainScreen(
                startBluetoothService = startBluetoothService,
                pairedDevices = pairedDevices,
                refreshPairedDevices = refreshPairedDevices,
                stopBluetoothService = stopBluetoothService,
                navController = navController,
                settingsViewModel = settingsViewModel
            )
        }

        composable<Screens.SettingsScreen> {
            SettingsScreen(
                navController = navController,
                settingsViewModel = settingsViewModel,
            )
        }

        composable<Screens.SupportScreen> {
            SupportScreen(
                navController = navController,
            )
        }

        composable<Screens.BluetoothScannerScreen> {
            BluetoothScannerScreen(
                navController = navController,
                discoveredDevices = discoveredDevices,
                isScanning = isScanning,
                bluetoothEnabled = bluetoothEnabled,
                onStartScan = onStartScan,
                onPairDevice = onPairDevice,
            )
        }
    }
}

object Screens {
    @Serializable
    object MainScreen

    @Serializable
    object SettingsScreen

    @Serializable
    object SupportScreen

    @Serializable
    object BluetoothScannerScreen
}