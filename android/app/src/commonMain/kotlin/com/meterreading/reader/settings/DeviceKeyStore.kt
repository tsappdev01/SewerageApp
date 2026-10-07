package com.meterreading.reader.settings

import com.meterreading.reader.api.DeviceCredentials
import com.meterreading.reader.data.Sealer
import com.meterreading.reader.platform.KeyValueStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Keeps this phone's registration (FR-002.1). The secret device key is encrypted with a key held by
 * the platform's key store (Android Keystore, iOS Keychain). Cleared with the app's data, like the
 * supervisor PIN.
 */
@OptIn(ExperimentalEncodingApi::class)
class DeviceKeyStore(private val prefs: KeyValueStore, private val sealer: Sealer) {
    fun load(): DeviceCredentials? {
        val id = prefs.getString(KEY_ID) ?: return null
        val sealed = prefs.getString(KEY_SECRET) ?: return null
        val label = prefs.getString(KEY_LABEL).orEmpty()
        return runCatching {
            DeviceCredentials(id, sealer.open(Base64.decode(sealed)).decodeToString(), label)
        }.getOrNull()
    }

    fun save(device: DeviceCredentials) {
        prefs.putString(KEY_ID, device.deviceId)
        prefs.putString(KEY_SECRET, Base64.encode(sealer.seal(device.deviceKey.encodeToByteArray())))
        prefs.putString(KEY_LABEL, device.label)
    }

    private companion object {
        const val KEY_ID = "device_id"
        const val KEY_SECRET = "device_key_sealed"
        const val KEY_LABEL = "device_label"
    }
}
