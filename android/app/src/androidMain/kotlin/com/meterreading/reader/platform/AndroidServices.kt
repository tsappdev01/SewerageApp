package com.meterreading.reader.platform

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.meterreading.reader.BuildConfig
import com.meterreading.reader.data.AesGcmSealer
import com.meterreading.reader.data.Sealer
import com.meterreading.reader.settings.AndroidConnectivity
import com.meterreading.reader.settings.AppServices
import com.meterreading.reader.settings.KeystoreKeys
import com.meterreading.reader.settings.UploadWorker

/** Starts the shared [AppServices] with Android's storage, Keystore and build values. Safe to call often. */
object AndroidPlatform {
    lateinit var appContext: Context
        private set

    fun start(context: Context) {
        if (::appContext.isInitialized && AppServices.isStarted) return
        appContext = context.applicationContext
        AppServices.init(AndroidServices(appContext))
    }
}

private class AndroidServices(private val context: Context) : PlatformServices {
    override val prefs: KeyValueStore = SharedPreferencesStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    // noBackupFilesDir: never copied to cloud backups.
    override val dataDir: File = File(context.noBackupFilesDir.path)

    // One Keystore key for everything kept on the phone: the device key, the queue and its photos.
    override val sealer: Sealer = AesGcmSealer { KeystoreKeys.aes("meter-reading-data") }

    override val build = BuildValues(
        apiBaseUrl = BuildConfig.API_BASE_URL,
        useFakeData = BuildConfig.USE_FAKE_DATA,
        readerLogin = BuildConfig.READER_LOGIN,
        deviceLock = BuildConfig.DEVICE_LOCK,
        settingsPin = BuildConfig.SETTINGS_PIN,
        versionName = BuildConfig.VERSION_NAME,
        isDebug = BuildConfig.DEBUG,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        osVersion = Build.VERSION.RELEASE,
    )

    override fun startConnectivity() = AndroidConnectivity.start(context)

    override fun scheduleUploadCheck() = UploadWorker.scheduleCheck(context)
}

private class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
}
