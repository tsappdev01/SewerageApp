package com.meterreading.reader.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Where the phone is, to keep with an inspection visit as proof it took place (spec FR-031.2). */
data class Fix(val latitude: Double, val longitude: Double, val accuracyM: Double)

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * One location reading: a fresh one on Android 11+, else the last known. [onResult] gets null when
 * location is off or not allowed; the visit is then saved without it (a warning, never a block).
 */
@SuppressLint("MissingPermission")
fun currentLocation(context: Context, onResult: (Fix?) -> Unit) {
    if (!hasLocationPermission(context)) return onResult(null)
    val manager = context.getSystemService(LocationManager::class.java) ?: return onResult(null)
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return onResult(null)
    fun Location.toFix() = Fix(latitude, longitude, accuracy.toDouble())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        manager.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { location ->
            onResult((location ?: manager.getLastKnownLocation(provider))?.toFix())
        }
    } else {
        onResult(manager.getLastKnownLocation(provider)?.toFix())
    }
}
