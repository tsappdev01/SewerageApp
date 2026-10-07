package com.meterreading.reader.auth

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * FR-001.1: the phone's own lock opens the app — its PIN, pattern or password, or fingerprint or
 * face where the phone has them. Nothing is stored by the app; Android checks it.
 */
object DeviceLock {
    // Weak biometrics include most phones' face unlock; the PIN or pattern is always offered too.
    private const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    sealed interface Result {
        data object Unlocked : Result
        data object Cancelled : Result
        /** The phone has no screen lock at all: it must get one before the app can open. */
        data object NoLockSet : Result
        data class Failed(val message: String) : Result
    }

    fun isSetUp(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    /** Opens the phone's own screen-lock settings. */
    fun securitySettings(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS)

    suspend fun unlock(activity: FragmentActivity, title: String, subtitle: String): Result {
        if (!isSetUp(activity)) return Result.NoLockSet
        return suspendCancellableCoroutine { cont ->
            fun finish(result: Result) { if (cont.isActive) cont.resume(result) }
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(Result.Unlocked)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> Result.Cancelled
                            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_NO_BIOMETRICS -> Result.NoLockSet
                            else -> Result.Failed(errString.toString())
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
}
