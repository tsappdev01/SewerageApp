package com.meterreading.reader.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.meterreading.reader.BuildConfig
import com.meterreading.reader.api.ApiClient
import com.meterreading.reader.api.DeviceCredentials
import com.meterreading.reader.data.ApiMeterRepository
import com.meterreading.reader.data.AppGraph
import com.meterreading.reader.data.AppSettings
import com.meterreading.reader.data.FakeMeterRepository
import com.meterreading.reader.data.SupervisorPin
import com.meterreading.reader.data.UnlockPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings saved on the phone (gear icon, behind the supervisor PIN), the repository built from
 * them, the supervisor PIN, and whether the phone's lock must be asked for (FR-001.1, FR-001.5).
 * The build's values (gradle -PapiBaseUrl, -PreaderLogin, -PsettingsPin) are only first values.
 */
object AppServices {
    private lateinit var prefs: SharedPreferences
    private lateinit var deviceStore: DeviceKeyStore

    /** This phone's registration (FR-002), or null until a supervisor registers it in Settings. */
    var device: DeviceCredentials? = null
        private set

    /** Plain http only in debug builds; release builds need https. */
    val allowHttp: Boolean = BuildConfig.DEBUG

    var settings: AppSettings = defaults()
        private set

    private val _locked = MutableStateFlow(true)
    /** True while the app waits for the phone's lock; screens stay hidden behind the lock screen. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var unlocked = false
    private var backgroundSince: Long? = null

    fun init(appContext: Context) {
        if (::prefs.isInitialized) return
        prefs = appContext.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        deviceStore = DeviceKeyStore(prefs)
        device = deviceStore.load()
        // A PIN given at build time becomes the first supervisor PIN; only its hash is kept.
        if (storedPin() == null && SupervisorPin.isValid(BuildConfig.SETTINGS_PIN)) setPin(BuildConfig.SETTINGS_PIN)
        apply(load())
        _locked.value = settings.deviceLock
    }

    /** Saves and switches to the new settings. The app opens again from the start screen. */
    fun save(new: AppSettings) {
        prefs.edit()
            .putString(KEY_URL, new.apiBaseUrl)
            .putString(KEY_LOGIN, new.readerLogin)
            .putBoolean(KEY_LOCK, new.deviceLock)
            .apply()
        apply(new)
        if (!new.deviceLock) markUnlocked()
    }

    /**
     * Registers this phone with a one-time code from IT, against [serverUrl], and keeps its key.
     * Throws ApiException (e.g. REGISTRATION_CODE_INVALID) or IOException (no connection).
     */
    suspend fun registerDevice(serverUrl: String, code: String): DeviceCredentials {
        val registered = ApiClient(serverUrl).registerDevice(code, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE, BuildConfig.VERSION_NAME)
        deviceStore.save(registered)
        device = registered
        apply(settings)
        return registered
    }

    // --- phone lock ---

    fun markUnlocked() {
        unlocked = true
        backgroundSince = null
        _locked.value = false
    }

    fun onBackground(nowMillis: Long) {
        if (unlocked) backgroundSince = nowMillis
    }

    fun onForeground(nowMillis: Long) {
        if (UnlockPolicy.needsUnlock(settings.deviceLock, unlocked, backgroundSince, nowMillis)) {
            unlocked = false
            _locked.value = true
        }
        backgroundSince = null
    }

    // --- supervisor PIN ---

    fun hasPin(): Boolean = storedPin() != null

    fun setPin(pin: String) {
        val stored = SupervisorPin.create(pin)
        prefs.edit().putString(KEY_PIN_SALT, stored.salt).putString(KEY_PIN_HASH, stored.hash)
            .putInt(KEY_PIN_FAILURES, 0).putLong(KEY_PIN_BLOCKED, 0).apply()
    }

    fun checkPin(pin: String, nowMillis: Long = System.currentTimeMillis()): SupervisorPin.Result {
        val stored = storedPin() ?: return SupervisorPin.Result.Ok(SupervisorPin.Attempts())
        val attempts = SupervisorPin.Attempts(prefs.getInt(KEY_PIN_FAILURES, 0), prefs.getLong(KEY_PIN_BLOCKED, 0))
        val result = SupervisorPin.check(pin, stored, attempts, nowMillis)
        val next = when (result) {
            is SupervisorPin.Result.Ok -> result.attempts
            is SupervisorPin.Result.Wrong -> result.attempts
            is SupervisorPin.Result.Blocked -> result.attempts
        }
        prefs.edit().putInt(KEY_PIN_FAILURES, next.failures).putLong(KEY_PIN_BLOCKED, next.blockedUntilMillis).apply()
        return result
    }

    private fun storedPin(): SupervisorPin.Stored? {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return null
        val hash = prefs.getString(KEY_PIN_HASH, null) ?: return null
        return SupervisorPin.Stored(salt, hash)
    }

    // --- settings ---

    private fun apply(s: AppSettings) {
        settings = s
        val client = ApiClient(s.apiBaseUrl).apply { device = this@AppServices.device }
        AppGraph.repository = if (BuildConfig.USE_FAKE_DATA) FakeMeterRepository() else ApiMeterRepository(client)
    }

    private fun load(): AppSettings {
        val d = defaults()
        return AppSettings(
            apiBaseUrl = prefs.getString(KEY_URL, null) ?: d.apiBaseUrl,
            readerLogin = prefs.getString(KEY_LOGIN, null) ?: d.readerLogin,
            deviceLock = prefs.getBoolean(KEY_LOCK, d.deviceLock),
        )
    }

    private fun defaults() = AppSettings(
        apiBaseUrl = BuildConfig.API_BASE_URL,
        readerLogin = BuildConfig.READER_LOGIN,
        deviceLock = BuildConfig.DEVICE_LOCK,
    )

    private const val KEY_URL = "api_base_url"
    private const val KEY_LOGIN = "reader_login"
    private const val KEY_LOCK = "device_lock"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_FAILURES = "pin_failures"
    private const val KEY_PIN_BLOCKED = "pin_blocked_until"
}
