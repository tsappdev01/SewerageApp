package com.meterreading.reader.settings

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.meterreading.reader.api.DeviceCredentials
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps this phone's registration (FR-002.1). The secret device key is encrypted with an AES key
 * that lives in the Android Keystore and never leaves it, so copying the app's files does not
 * give the key away. Cleared with the app's data, like the supervisor PIN.
 */
class DeviceKeyStore(private val prefs: SharedPreferences) {
    fun load(): DeviceCredentials? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val sealed = prefs.getString(KEY_SECRET, null) ?: return null
        val label = prefs.getString(KEY_LABEL, null).orEmpty()
        return runCatching { DeviceCredentials(id, open(sealed), label) }.getOrNull()
    }

    fun save(device: DeviceCredentials) {
        prefs.edit()
            .putString(KEY_ID, device.deviceId)
            .putString(KEY_SECRET, seal(device.deviceKey))
            .putString(KEY_LABEL, device.label)
            .apply()
    }

    private fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val bytes = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "meter-reading-device-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val KEY_ID = "device_id"
        const val KEY_SECRET = "device_key_sealed"
        const val KEY_LABEL = "device_label"
    }
}
