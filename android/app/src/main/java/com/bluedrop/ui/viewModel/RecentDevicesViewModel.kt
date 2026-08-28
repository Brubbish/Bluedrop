package com.bluedrop.ui.viewModel

import androidx.lifecycle.ViewModel
import com.bluedrop.core.RecentDevicesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RecentDevicesViewModel(private val manager: RecentDevicesManager) : ViewModel() {
    private val _recentItems = MutableStateFlow(manager.getAll())
    val recentItems: StateFlow<List<String>> = _recentItems

    fun addRecentDevice(item: String) {
        manager.add(item)
        _recentItems.value = manager.getAll()
    }

//    fun removeRecent(item: String) {
//        manager.remove(item)
//        _recentItems.value = manager.getAll()
//    }

//    fun clearAll() {
//        manager.clear()
//        _recentItems.value = emptyList()
//    }
}