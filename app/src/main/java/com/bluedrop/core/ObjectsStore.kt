package com.bluedrop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global service state manager for Bluedrop.
 *
 * This singleton manages the session state of the BluetoothService,
 * providing thread-safe, observable properties.
 */
object Essentials {
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _selectedDevices = MutableStateFlow<Set<String>>(emptySet())
    val selectedDevices: StateFlow<Set<String>> = _selectedDevices.asStateFlow()

    private val _autoCopy = MutableStateFlow(true)
    val autoCopy: StateFlow<Boolean> = _autoCopy.asStateFlow()

    fun setServiceRunning(isRunning: Boolean) {
        _isServiceRunning.value = isRunning
    }

    fun updateSelectedDevices(devices: Set<String>) {
        _selectedDevices.value = devices
    }

    fun updateAutoCopy(isEnabled: Boolean) {
        _autoCopy.value = isEnabled
    }

    /**
     * Safely cleans up all session state.
     * Should be called when the service is stopped.
     */
    fun clear() {
        _isServiceRunning.value = false
        _selectedDevices.value = emptySet()
    }
}