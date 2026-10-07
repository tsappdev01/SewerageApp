package com.meterreading.reader.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Keeps the shared [Connectivity] up to date from Android's ConnectivityManager (FR-020.4). */
object AndroidConnectivity {
    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val manager = context.getSystemService(ConnectivityManager::class.java)
        Connectivity.update(manager.getNetworkCapabilities(manager.activeNetwork).isUsable())
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                Connectivity.update(capabilities.isUsable())
            }

            override fun onLost(network: Network) {
                Connectivity.update(false)
            }
        })
    }

    // VALIDATED: Android has checked the connection really reaches the internet (not a captive portal).
    private fun NetworkCapabilities?.isUsable(): Boolean =
        this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
