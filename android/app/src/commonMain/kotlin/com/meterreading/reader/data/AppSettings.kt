package com.meterreading.reader.data

import com.meterreading.reader.platform.*
/**
 * What the Settings screen changes (gear icon, behind the supervisor PIN): the server, the reader
 * this phone belongs to, and whether the phone's own lock is asked for. Saved on the phone; the
 * build only supplies the first values.
 */
data class AppSettings(
    /** The Meter Reading API, e.g. https://meterreading-api.example/ (always ends with "/"). */
    val apiBaseUrl: String,
    /** The reader this phone belongs to: their LoginEmail in vw_MR_Reader. Set by the supervisor. */
    val readerLogin: String = "",
    /**
     * FR-001.1: ask for the phone's own lock (PIN, pattern, fingerprint or face) before the app
     * opens, and again after it has been in the background for [UnlockPolicy.TIMEOUT_MINUTES].
     */
    val deviceLock: Boolean = true,
)

object SettingsRules {
    private val email = Regex("^[^@\\s]+@[^@\\s]+$")

    /**
     * The address as the app uses it (trimmed, ending with "/"), or null if it is not a web address.
     * Plain http is allowed only in test builds ([allowHttp]); phones must use https.
     */
    fun normalizeUrl(text: String, allowHttp: Boolean): String? {
        val url = text.trim()
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "https" && !(allowHttp && scheme == "http")) return null
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host.isBlank() || host.contains(' ')) return null
        return url.trimEnd('/') + "/"
    }

    /** Problems to show before saving, in plain words. Empty: the settings can be saved. */
    fun problems(s: AppSettings, allowHttp: Boolean): List<String> = buildList {
        if (normalizeUrl(s.apiBaseUrl, allowHttp) == null) {
            add(if (allowHttp) "Server address must start with https:// or http://" else "Server address must start with https://")
        }
        if (!email.matches(s.readerLogin.trim())) add("Reader email is needed, e.g. rashid@dip.ae")
    }

    /** Trimmed values with the normalized address; call after [problems] is empty. */
    fun cleaned(s: AppSettings, allowHttp: Boolean): AppSettings = s.copy(
        apiBaseUrl = normalizeUrl(s.apiBaseUrl, allowHttp) ?: s.apiBaseUrl.trim(),
        readerLogin = s.readerLogin.trim(),
    )
}

/** FR-001.5: when the phone's lock must be asked for again. */
object UnlockPolicy {
    const val TIMEOUT_MINUTES = 15L

    /**
     * True when the app must ask for the phone's lock: lock on, and never unlocked since the app
     * started, or back after [TIMEOUT_MINUTES] or more in the background.
     */
    fun needsUnlock(lockOn: Boolean, unlocked: Boolean, backgroundSinceMillis: Long?, nowMillis: Long): Boolean =
        lockOn && (!unlocked || (backgroundSinceMillis != null && nowMillis - backgroundSinceMillis >= TIMEOUT_MINUTES * 60_000))
}
