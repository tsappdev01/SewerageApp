package com.meterreading.reader.platform

import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.meterreading.reader.util.findActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

@Composable
actual fun SecureScreen() {
    if (!com.meterreading.reader.BuildConfig.SECURE_SCREENS) return
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
actual fun rememberDeviceUnlock(): DeviceUnlock {
    val context = LocalContext.current
    return remember(context) { AndroidDeviceUnlock(context.findActivity() as? FragmentActivity, context) }
}

/**
 * FR-001.1: the phone's own lock opens the app — its PIN, pattern or password, or fingerprint or
 * face where the phone has them. Nothing is stored by the app; Android checks it.
 */
private class AndroidDeviceUnlock(
    private val activity: FragmentActivity?,
    private val context: android.content.Context,
) : DeviceUnlock {
    override fun isSetUp(): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    override fun openSecuritySettings() {
        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override suspend fun unlock(title: String, subtitle: String): UnlockResult {
        val host = activity ?: return UnlockResult.Failed("The lock cannot be shown here.")
        if (!isSetUp()) return UnlockResult.NoLockSet
        return suspendCancellableCoroutine { cont ->
            fun finish(result: UnlockResult) { if (cont.isActive) cont.resume(result) }
            val prompt = BiometricPrompt(
                host,
                ContextCompat.getMainExecutor(host),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(UnlockResult.Unlocked)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> UnlockResult.Cancelled
                            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_NO_BIOMETRICS -> UnlockResult.NoLockSet
                            else -> UnlockResult.Failed(errString.toString())
                        },
                    )
                    // onAuthenticationFailed is one wrong finger or face; the prompt stays open for another try.
                },
            )
            // With DEVICE_CREDENTIAL allowed there is no cancel button: the phone's PIN is the fallback.
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(ALLOWED)
                    .build(),
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    private companion object {
        // Weak biometrics include most phones' face unlock; the PIN or pattern is always offered too.
        const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
