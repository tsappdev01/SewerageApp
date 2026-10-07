package com.meterreading.reader.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone has a working internet connection now (FR-020.4). The platform keeps it up to
 * date: Android's ConnectivityManager, iOS's NWPathMonitor ([com.meterreading.reader.platform.PlatformServices.startConnectivity]).
 */
object Connectivity {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun update(connected: Boolean) {
        _connected.value = connected
    }
}
