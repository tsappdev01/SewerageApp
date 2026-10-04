package com.meterreading.reader.settings

import android.content.SharedPreferences
import android.util.Base64
import com.meterreading.reader.api.DeviceCredentials
import com.meterreading.reader.data.Sealer

/**
 * Keeps this phone's registration (FR-002.1). The secret device key is encrypted with a key that
 * lives in the Android Keystore ([KeystoreKeys]). Cleared with the app's data, like the supervisor PIN.
 */
class DeviceKeyStore(private val prefs: SharedPreferences, private val sealer: Sealer) {
    fun load(): DeviceCredentials? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val sealed = prefs.getString(KEY_SECRET, null) ?: return null
        val label = prefs.getString(KEY_LABEL, null).orEmpty()
        return runCatching {
            DeviceCredentials(id, sealer.open(Base64.decode(sealed, Base64.NO_WRAP)).toString(Charsets.UTF_8), label)
        }.getOrNull()
    }

    fun save(device: DeviceCredentials) {
        prefs.edit()
            .putString(KEY_ID, device.deviceId)
            .putString(KEY_SECRET, Base64.encodeToString(sealer.seal(device.deviceKey.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .putString(KEY_LABEL, device.label)
            .apply()
    }

    private companion object {
        const val KEY_ID = "device_id"
        const val KEY_SECRET = "device_key_sealed"
        const val KEY_LABEL = "device_label"
    }
}
