package com.bluedrop.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Support
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.bluedrop.ui.component.AppInfoCard
import com.bluedrop.ui.component.SettingItem
import com.bluedrop.ui.component.WindowsCompanionCard
import com.bluedrop.ui.navigation.Screens
import com.bluedrop.ui.navigation.safePopBackStack
import com.bluedrop.ui.viewModel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
) {
    var showResetDialog by remember { mutableStateOf(false) }
    val autoCopy by settingsViewModel.autoCopy.collectAsStateWithLifecycle()
    val isDarkMode by settingsViewModel.isDarkMode.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors()
                    .copy(containerColor = Color.Transparent),
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onPrimaryContainer
                    )
                },
                actions = {
                    TextButton(onClick = {
                        showResetDialog = true
                    }) {
                        Text(
                            "Reset", color = colorScheme.onPrimaryContainer,
                            fontSize = 18.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.safePopBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colorScheme.onPrimaryContainer
                        )
                    }
                }
            )
        }) { innerPadding ->

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (showResetDialog) {
                AlertDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = { Text("Reset Settings") },
                    text = { Text("Are you sure you want to reset all settings to default?") },
                    confirmButton = {
                        TextButton(onClick = {
                            settingsViewModel.resetSettings()
                            showResetDialog = false
                        }) {
                            Text("Reset", color = colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            AppInfoCard()
            WindowsCompanionCard()

            SettingItem(
                "Auto Copy", "Automatically copy received text", Icons.Default.ContentCopy,
                actionButton = {
                    Switch(
                        checked = autoCopy,
                        onCheckedChange = {
                            settingsViewModel.toggleAutoCopy()
                        }
                    )
                },
                pressAction = { settingsViewModel.toggleAutoCopy() }
            )

            SettingItem(
                "Dark Theme",
                "Toggle between dark and light theme",
                if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                actionButton = {
                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = { settingsViewModel.switchTheme() },
                    )
                },
                pressAction = { settingsViewModel.switchTheme() }
            )

            SettingItem(
                "Support",
                "Get help and view FAQs",
                Icons.Default.Support,
                pressAction = { navController.navigate(Screens.SupportScreen) }
            )

            SettingItem(
                "Scan",
                "Pair new devices",
                Icons.AutoMirrored.Filled.BluetoothSearching,
                pressAction = { navController.navigate(Screens.BluetoothScannerScreen) }
            )
        }
    }
}