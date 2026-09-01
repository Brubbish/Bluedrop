package com.bluedrop.ui.screen

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.bluedrop.core.Essentials
import com.bluedrop.core.RecentDevicesManager
import com.bluedrop.ui.component.ActionButtons
import com.bluedrop.ui.component.CustomPullToRefreshBox
import com.bluedrop.ui.component.DarkModeToggle
import com.bluedrop.ui.component.DeviceItem
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bluedrop.workManagers.ShareClipboardWorker
import com.bluedrop.ui.navigation.Screens
import com.bluedrop.ui.theme.Typography
import com.bluedrop.ui.viewModel.RecentDevicesViewModel
import com.bluedrop.ui.viewModel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    startBluetoothService: () -> Unit,
    pairedDevices: Set<BluetoothDevice>,
    refreshPairedDevices: () -> Unit,
    stopBluetoothService: () -> Unit,
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val recentDevicesViewModel: RecentDevicesViewModel =
        viewModel { RecentDevicesViewModel(RecentDevicesManager(context)) }
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showTypeToSend by remember { mutableStateOf(false) }


    val isServiceBound by Essentials.isServiceRunning.collectAsStateWithLifecycle()
    val selectedDeviceAddresses by Essentials.selectedDevices.collectAsStateWithLifecycle()
    val isDarkMode by settingsViewModel.isDarkMode.collectAsStateWithLifecycle()
    val recentDevices by recentDevicesViewModel.recentItems.collectAsStateWithLifecycle()

    CustomPullToRefreshBox(
        refreshPairedDevices = refreshPairedDevices,
        modifier = Modifier
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = colorScheme.background,
            topBar = {
                LargeTopAppBar(
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors()
                        .copy(
                            containerColor = colorScheme.primaryContainer,
                            scrolledContainerColor = colorScheme.primaryContainer
                        ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Bluedrop",
                                fontWeight = FontWeight.SemiBold,
                                color = colorScheme.onPrimaryContainer
                            )
                            AnimatedVisibility(
                                selectedDeviceAddresses.isNotEmpty(),
                                modifier = Modifier.padding(horizontal = 10.dp)
                            ) {
                                Text(
                                    text = "${selectedDeviceAddresses.count()} selected",
                                    fontSize = 18.sp,
                                    color = colorScheme.onPrimaryContainer,
                                )
                            }
                            AnimatedVisibility(selectedDeviceAddresses.isNotEmpty()) {
                                Icon(
                                    Icons.Default.Cancel,
                                    contentDescription = "Deselect devices",
                                    tint = colorScheme.error,
                                    modifier = Modifier.clickable {
                                        scope.launch {
                                            Essentials.updateSelectedDevices(emptySet())
                                        }
                                    }
                                )
                            }
                        }
                    },
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(15.dp),
                            modifier = Modifier.padding(end = 10.dp)
                        ) {
                            DarkModeToggle(
                                isDarkMode = isDarkMode,
                                onToggle = { settingsViewModel.switchTheme() },
                            )

                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Type to send",
                                tint = colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .size(25.dp)
                                    .clickable { showTypeToSend = true }
                            )

                            Icon(
                                Icons.Default.History,
                                contentDescription = "Transfer history",
                                tint = colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .size(25.dp)
                                    .clickable {
                                        navController.navigate(Screens.HistoryScreen)
                                    }
                            )

                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings Button",
                                tint = colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .size(25.dp)
                                    .clickable {
                                        navController.navigate(Screens.SettingsScreen)
                                    }
                            )
                        }
                    },
                )
            }
        ) { padding ->
            if (showTypeToSend) {
                TypeToSendDialog(
                    onDismiss = { showTypeToSend = false },
                    onSend = { text ->
                        showTypeToSend = false
                        val inputData = Data.Builder()
                            .putString(ShareClipboardWorker.KEY_CLIP_TEXT, text)
                            .build()
                        WorkManager.getInstance(context).enqueue(
                            OneTimeWorkRequestBuilder<ShareClipboardWorker>()
                                .setInputData(inputData)
                                .build()
                        )
                    },
                )
            }
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Select devices to share clipboard with",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val recentDevicesList = pairedDevices.filter { it.address in recentDevices }
                    val otherDevicesList = pairedDevices.filterNot { it.address in recentDevices }

                    if (recentDevicesList.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Recent Devices",
                                icon = Icons.Default.Timeline,
                                description = "Recent Devices List"
                            )
                        }

                        items(recentDevicesList) { device ->
                            val name = if (ActivityCompat.checkSelfPermission(
                                    context, Manifest.permission.BLUETOOTH_CONNECT
                                ) != PackageManager.PERMISSION_GRANTED
                            ) "Unknown Device" else device.name ?: "Unknown Device"
                            val address = device.address
                            val isSelected = selectedDeviceAddresses.contains(address)

                            DeviceItem(
                                onChecked = {
                                    scope.launch {
                                        val updatedDevices = if (!isSelected)
                                            selectedDeviceAddresses + address
                                        else selectedDeviceAddresses - address
                                        Essentials.updateSelectedDevices(updatedDevices)
                                    }
                                },
                                checked = isSelected,
                                name = name,
                            )
                        }
                    }

                    item {
                        Spacer(Modifier.height(20.dp))
                    }

                    item {
                        SectionHeader(
                            title = "Paired Devices",
                            icon = Icons.Default.Bluetooth,
                            description = "Paired Devices List"
                        )
                    }

                    items(otherDevicesList) { device ->
                        val name = if (ActivityCompat.checkSelfPermission(
                                context, Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) "Unknown Device" else device.name ?: "Unknown Device"
                        val address = device.address
                        val isSelected = selectedDeviceAddresses.contains(address)

                        DeviceItem(
                            onChecked = {
                                if (!isSelected) {
                                    recentDevicesViewModel.addRecentDevice(address)
                                }
                                scope.launch {
                                    val updatedDevices = if (!isSelected)
                                        selectedDeviceAddresses + address
                                    else selectedDeviceAddresses - address
                                    Essentials.updateSelectedDevices(updatedDevices)
                                }
                            },
                            checked = isSelected,
                            name = name,
                        )
                    }
                }

                ActionButtons(
                    startBluetoothService = startBluetoothService,
                    stopBluetoothService = stopBluetoothService,
                    selectedDeviceAddresses = selectedDeviceAddresses,
                    scope = scope,
                    context = context,
                    isServiceBound = isServiceBound,
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector, description: String = "") {
    Row {
        Icon(
            icon,
            contentDescription = description,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontWeight = FontWeight.SemiBold,
            style = Typography.bodyMedium,
        )
    }
}