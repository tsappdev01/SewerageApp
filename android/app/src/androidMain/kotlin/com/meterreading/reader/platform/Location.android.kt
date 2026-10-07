package com.meterreading.reader.platform

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * One location reading: a fresh one on Android 11+, else the last known. [onResult] gets null when
 * location is off or not allowed; the visit is then saved without it (a warning, never a block).
 */
@SuppressLint("MissingPermission")
private fun currentLocation(context: Context, onResult: (Fix?) -> Unit) {
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

@Composable
actual fun rememberLocationRequest(onResult: (Fix?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onResult)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        currentLocation(context) { callback.value(it) }
    }
    return {
        if (hasLocationPermission(context)) currentLocation(context) { callback.value(it) }
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}
