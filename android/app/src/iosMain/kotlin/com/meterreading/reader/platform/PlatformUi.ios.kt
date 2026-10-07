@file:OptIn(ExperimentalForeignApi::class)

package com.meterreading.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/** iOS has no back button; screens have their own back arrow. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {}

/**
 * iOS does not let an app block screenshots. The app's data stays encrypted on the phone (Keychain
 * key, file protection); the screens themselves can be photographed like any other.
 */
@Composable
actual fun SecureScreen() {}

/**
 * Voice search is not offered on iOS yet: the app says so and the reader types instead (the iOS
 * keyboard's own dictation button still works in every text box).
 */
@Composable
actual fun rememberVoiceInput(prompt: String, onText: (String) -> Unit): () -> Boolean = { false }

@Composable
actual fun rememberLocationRequest(onResult: (Fix?) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onResult)
    val locator = remember { Locator { callback.value(it) } }
    return { locator.request() }
}

/** One location reading with CoreLocation, asking for "while using the app" permission first. */
private class Locator(private val onResult: (Fix?) -> Unit) {
    private val manager = CLLocationManager()
    private var waiting = false

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return finish(null)
            location.coordinate.useContents { finish(Fix(latitude, longitude, location.horizontalAccuracy)) }
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) = finish(null)

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (!waiting) return
            when (manager.authorizationStatus) {
                kCLAuthorizationStatusNotDetermined -> {}
                kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> manager.requestLocation()
                else -> finish(null)
            }
        }
    }

    init {
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    fun request() {
        waiting = true
        val status: CLAuthorizationStatus = manager.authorizationStatus
        when (status) {
            kCLAuthorizationStatusNotDetermined -> manager.requestWhenInUseAuthorization()
            kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> manager.requestLocation()
            else -> finish(null)
        }
    }

    private fun finish(fix: Fix?) {
        if (!waiting) return
        waiting = false
        onResult(fix)
    }
}

@Composable
actual fun rememberDeviceUnlock(): DeviceUnlock = remember { IosDeviceUnlock() }

/** FR-001.1 on iOS: Face ID, Touch ID or the passcode. Nothing is stored by the app; iOS checks it. */
private class IosDeviceUnlock : DeviceUnlock {
    override fun isSetUp(): Boolean = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)

    override suspend fun unlock(title: String, subtitle: String): UnlockResult {
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)) return UnlockResult.NoLockSet
        return suspendCancellableCoroutine { cont ->
            context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, localizedReason = subtitle) { ok, error ->
                val result = when {
                    ok -> UnlockResult.Unlocked
                    error == null -> UnlockResult.Cancelled
                    error.code in listOf(LAErrorUserCancel, LAErrorAppCancel, LAErrorSystemCancel) -> UnlockResult.Cancelled
                    error.code == LAErrorPasscodeNotSet -> UnlockResult.NoLockSet
                    else -> UnlockResult.Failed(error.localizedDescription)
                }
                dispatch_async(dispatch_get_main_queue()) { if (cont.isActive) cont.resume(result) }
            }
            cont.invokeOnCancellation { context.invalidate() }
        }
    }

    override fun openSecuritySettings() {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
            UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any?>(), completionHandler = null)
        }
    }
}
