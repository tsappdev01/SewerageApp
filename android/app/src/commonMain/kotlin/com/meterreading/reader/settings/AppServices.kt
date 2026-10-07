package com.meterreading.reader.settings

import com.meterreading.reader.api.ApiClient
import com.meterreading.reader.api.DeviceCredentials
import com.meterreading.reader.data.ApiMeterRepository
import com.meterreading.reader.data.AppGraph
import com.meterreading.reader.data.AppSettings
import com.meterreading.reader.data.FakeInspectionApi
import com.meterreading.reader.data.FakeMeterRepository
import com.meterreading.reader.data.InspectionRepository
import com.meterreading.reader.data.InspectionStore
import com.meterreading.reader.data.MeterListCache
import com.meterreading.reader.data.PhotoVault
import com.meterreading.reader.data.QueueStore
import com.meterreading.reader.data.SupervisorPin
import com.meterreading.reader.data.SyncPrompt
import com.meterreading.reader.data.UnlockPolicy
import com.meterreading.reader.platform.File
import com.meterreading.reader.platform.KeyValueStore
import com.meterreading.reader.platform.PlatformServices
import com.meterreading.reader.platform.currentTimeMillis
import com.meterreading.reader.platform.ofEpochMilli
import com.meterreading.reader.platform.toEpochMilli
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * Settings saved on the phone (gear icon, behind the supervisor PIN), the repository built from
 * them, the supervisor PIN, and whether the phone's lock must be asked for (FR-001.1, FR-001.5).
 * The build's values (gradle -PapiBaseUrl, -PreaderLogin, -PsettingsPin) are only first values.
 * Shared by Android and iOS; the platform supplies storage, encryption and its build values.
 */
object AppServices {
    private lateinit var platform: PlatformServices
    private lateinit var prefs: KeyValueStore
    private lateinit var deviceStore: DeviceKeyStore
    private lateinit var queueStore: QueueStore
    private lateinit var photoVault: PhotoVault
    private lateinit var listCache: MeterListCache
    private lateinit var inspectionStore: InspectionStore

    /** This phone's registration (FR-002), or null until a supervisor registers it in Settings. */
    var device: DeviceCredentials? = null
        private set

    /** Plain http only in test builds; release builds need https. */
    val allowHttp: Boolean get() = platform.build.isDebug

    /** The app's version, shown at the bottom of Settings. */
    val versionName: String get() = platform.build.versionName

    /** Where photos are written as they are taken (inside the app's private, not backed-up storage). */
    val capturesDir: File get() = File(platform.dataDir, "captures")

    var settings: AppSettings = AppSettings(apiBaseUrl = "")
        private set

    /** True while the app is on screen: then a dialog asks to send, not a notification. */
    @Volatile
    var inForeground = false

    /** "Later" was chosen: do not ask to send before this time (FR-020.4). */
    val snoozedUntil: Instant?
        get() = prefs.getLong(KEY_SNOOZE, 0).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }

    fun snooze(now: Instant, length: Duration = SyncPrompt.SNOOZE) {
        prefs.putLong(KEY_SNOOZE, now.plus(length).toEpochMilli())
    }

    private val _locked = MutableStateFlow(true)
    /** True while the app waits for the phone's lock; screens stay hidden behind the lock screen. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var unlocked = false
    private var backgroundSince: Long? = null

    fun init(platform: PlatformServices) {
        if (::prefs.isInitialized) return
        this.platform = platform
        prefs = platform.prefs
        // One key for everything kept on the phone: the device key, the queue and its photos.
        val sealer = platform.sealer
        deviceStore = DeviceKeyStore(prefs, sealer)
        queueStore = QueueStore(File(platform.dataDir, "queue.mrq"), sealer)
        photoVault = PhotoVault(sealer)
        listCache = MeterListCache(File(platform.dataDir, "meters.mrq"), sealer)
        inspectionStore = InspectionStore(File(platform.dataDir, "inspections.mrq"), sealer)
        capturesDir.mkdirs()
        device = deviceStore.load()
        // A PIN given at build time becomes the first supervisor PIN; only its hash is kept.
        if (storedPin() == null && SupervisorPin.isValid(platform.build.settingsPin)) setPin(platform.build.settingsPin)
        apply(load())
        _locked.value = settings.deviceLock
        platform.startConnectivity()
    }

    /** True once [init] has run (the app's start). */
    val isStarted: Boolean get() = ::prefs.isInitialized

    /** Saves and switches to the new settings. The app opens again from the start screen. */
    fun save(new: AppSettings) {
        prefs.putString(KEY_URL, new.apiBaseUrl)
        prefs.putString(KEY_LOGIN, new.readerLogin)
        prefs.putBoolean(KEY_LOCK, new.deviceLock)
        apply(new)
        if (!new.deviceLock) markUnlocked()
    }

    /**
     * Registers this phone with a one-time code from IT, against [serverUrl], and keeps its key.
     * Throws ApiException (e.g. REGISTRATION_CODE_INVALID) or IOException (no connection).
     */
    suspend fun registerDevice(serverUrl: String, code: String): DeviceCredentials {
        val b = platform.build
        val registered = ApiClient(serverUrl).registerDevice(code, b.deviceModel, b.osVersion, b.versionName)
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
        prefs.putString(KEY_PIN_SALT, stored.salt)
        prefs.putString(KEY_PIN_HASH, stored.hash)
        prefs.putInt(KEY_PIN_FAILURES, 0)
        prefs.putLong(KEY_PIN_BLOCKED, 0)
    }

    fun checkPin(pin: String, nowMillis: Long = currentTimeMillis()): SupervisorPin.Result {
        val stored = storedPin() ?: return SupervisorPin.Result.Ok(SupervisorPin.Attempts())
        val attempts = SupervisorPin.Attempts(prefs.getInt(KEY_PIN_FAILURES, 0), prefs.getLong(KEY_PIN_BLOCKED, 0))
        val result = SupervisorPin.check(pin, stored, attempts, nowMillis)
        val next = when (result) {
            is SupervisorPin.Result.Ok -> result.attempts
            is SupervisorPin.Result.Wrong -> result.attempts
            is SupervisorPin.Result.Blocked -> result.attempts
        }
        prefs.putInt(KEY_PIN_FAILURES, next.failures)
        prefs.putLong(KEY_PIN_BLOCKED, next.blockedUntilMillis)
        return result
    }

    private fun storedPin(): SupervisorPin.Stored? {
        val salt = prefs.getString(KEY_PIN_SALT) ?: return null
        val hash = prefs.getString(KEY_PIN_HASH) ?: return null
        return SupervisorPin.Stored(salt, hash)
    }

    // --- settings ---

    private fun apply(s: AppSettings) {
        settings = s
        // The reader is known from Settings, so the background upload can send without a sign-in screen.
        val client = ApiClient(s.apiBaseUrl).apply {
            device = this@AppServices.device
            devUser = s.readerLogin.ifBlank { null }
        }
        val meters = if (platform.build.useFakeData) {
            FakeMeterRepository()
        } else {
            ApiMeterRepository(
                client, store = queueStore, vault = photoVault, onWaiting = { platform.scheduleUploadCheck() }, listCache = listCache,
            )
        }
        AppGraph.repository = meters
        // Field inspection uses the same server, phone key, reader and encryption (spec §21).
        AppGraph.inspections = InspectionRepository(
            api = if (platform.build.useFakeData) FakeInspectionApi { meters.online.value } else client,
            store = inspectionStore,
            vault = photoVault,
            onWaiting = { platform.scheduleUploadCheck() },
            onSignInNeeded = { reason ->
                meters.signInReason = reason
                meters.signInNeeded.value = true
            },
        )
    }

    private fun load(): AppSettings {
        val d = defaults()
        return AppSettings(
            apiBaseUrl = prefs.getString(KEY_URL) ?: d.apiBaseUrl,
            readerLogin = prefs.getString(KEY_LOGIN) ?: d.readerLogin,
            deviceLock = prefs.getBoolean(KEY_LOCK, d.deviceLock),
        )
    }

    private fun defaults() = AppSettings(
        apiBaseUrl = platform.build.apiBaseUrl,
        readerLogin = platform.build.readerLogin,
        deviceLock = platform.build.deviceLock,
    )

    private const val KEY_SNOOZE = "sync_snoozed_until"
    private const val KEY_URL = "api_base_url"
    private const val KEY_LOGIN = "reader_login"
    private const val KEY_LOCK = "device_lock"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_FAILURES = "pin_failures"
    private const val KEY_PIN_BLOCKED = "pin_blocked_until"
}
