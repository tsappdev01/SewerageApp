package com.meterreading.reader.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the phone has a working internet connection now, from Android (FR-020.4). */
object Connectivity {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val manager = context.getSystemService(ConnectivityManager::class.java)
        _connected.value = manager.getNetworkCapabilities(manager.activeNetwork).isUsable()
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _connected.value = capabilities.isUsable()
            }

            override fun onLost(network: Network) {
                _connected.value = false
            }
        })
    }

    // VALIDATED: Android has checked the connection really reaches the internet (not a captive portal).
    private fun NetworkCapabilities?.isUsable(): Boolean =
        this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
