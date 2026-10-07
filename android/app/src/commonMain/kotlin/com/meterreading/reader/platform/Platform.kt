package com.meterreading.reader.platform

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher

// What each platform supplies to the shared code (androidMain and iosMain have the actuals).

/** For file and other blocking work, off the main thread. */
expect val ioDispatcher: CoroutineDispatcher

/** Bytes from the platform's secure random generator (salts, IVs, keys). */
expect fun secureRandomBytes(size: Int): ByteArray

/** PBKDF2 with HMAC-SHA256: the slow hash of the supervisor PIN. */
expect fun pbkdf2HmacSha256(password: String, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray

/** The HTTP client the app talks to the server with: OkHttp on Android, NSURLSession on iOS. */
expect fun createHttpClient(): HttpClient

/** Short sound after a reading: an acknowledging tone when sent, a plain beep otherwise. */
expect fun playResultTone(sent: Boolean)

/**
 * Values the build gives as first values (the supervisor changes them in Settings) and facts about
 * the phone, for registration.
 */
data class BuildValues(
    val apiBaseUrl: String,
    val useFakeData: Boolean,
    val readerLogin: String,
    val deviceLock: Boolean,
    val settingsPin: String,
    val versionName: String,
    /** Test build: plain http allowed in Settings. */
    val isDebug: Boolean,
    val deviceModel: String,
    val osVersion: String,
)

/** Small values kept by the platform (SharedPreferences on Android, NSUserDefaults on iOS). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun getLong(key: String, default: Long): Long
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putString(key: String, value: String?)
    fun putLong(key: String, value: Long)
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
}

/** What [com.meterreading.reader.settings.AppServices] needs from the platform at start. */
interface PlatformServices {
    val prefs: KeyValueStore
    /** Private to the app and never copied to cloud backups: the queue, the lists, the photos. */
    val dataDir: File
    /** Encrypts what is kept on the phone, with a key held by the platform's key store. */
    val sealer: com.meterreading.reader.data.Sealer
    val build: BuildValues
    /** Starts following whether the phone is online ([com.meterreading.reader.settings.Connectivity]). */
    fun startConnectivity()
    /** Something is waiting on the phone: make sure the reader is asked to send it when signal is back. */
    fun scheduleUploadCheck()
}
