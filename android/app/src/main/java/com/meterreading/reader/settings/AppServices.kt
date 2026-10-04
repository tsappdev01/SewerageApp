package com.meterreading.reader.settings

import android.content.Context
import android.content.SharedPreferences
import com.meterreading.reader.BuildConfig
import com.meterreading.reader.api.ApiClient
import com.meterreading.reader.auth.CompanySignIn
import com.meterreading.reader.auth.MsalCompanySignIn
import com.meterreading.reader.data.ApiMeterRepository
import com.meterreading.reader.data.AppGraph
import com.meterreading.reader.data.AppSettings
import com.meterreading.reader.data.EntraSettings
import com.meterreading.reader.data.FakeMeterRepository

/**
 * Settings saved on the phone (gear icon), and the repository and company sign-in built from
 * them. The build's values (gradle -PapiBaseUrl, -PentraEnabled, …) are only the first values.
 */
object AppServices {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    /** Plain http only in debug builds; release builds need https. */
    val allowHttp: Boolean = BuildConfig.DEBUG

    var settings: AppSettings = defaults()
        private set

    /** Null while company sign-in is off: the test sign-in name is used. */
    var companySignIn: CompanySignIn? = null
        private set

    fun init(appContext: Context) {
        if (::prefs.isInitialized) return
        context = appContext.applicationContext
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        apply(load())
    }

    /** Saves and switches to the new settings. The reader signs in again afterwards. */
    suspend fun save(new: AppSettings) {
        // A wrong old configuration must not stop the new one from being saved.
        if (new.entraEnabled != settings.entraEnabled || new.entra != settings.entra) runCatching { companySignIn?.signOut() }
        prefs.edit()
            .putString(KEY_URL, new.apiBaseUrl)
            .putBoolean(KEY_ENTRA, new.entraEnabled)
            .putString(KEY_LOGIN, new.testLogin)
            .putString(KEY_TENANT, new.entra.tenantId)
            .putString(KEY_CLIENT, new.entra.clientId)
            .putString(KEY_REDIRECT, new.entra.redirectUri)
            .putString(KEY_SCOPE, new.entra.apiScope)
            .apply()
        apply(new)
    }

    private fun apply(s: AppSettings) {
        settings = s
        companySignIn = if (s.entraEnabled) MsalCompanySignIn(context, s.entra) else null
        val signIn = companySignIn
        val client = ApiClient(s.apiBaseUrl).apply { if (signIn != null) tokenSource = { signIn.token() } }
        AppGraph.repository = if (BuildConfig.USE_FAKE_DATA) FakeMeterRepository() else ApiMeterRepository(client)
    }

    private fun load(): AppSettings {
        val d = defaults()
        return AppSettings(
            apiBaseUrl = prefs.getString(KEY_URL, null) ?: d.apiBaseUrl,
            entraEnabled = prefs.getBoolean(KEY_ENTRA, d.entraEnabled),
            testLogin = prefs.getString(KEY_LOGIN, null) ?: d.testLogin,
            entra = EntraSettings(
                tenantId = prefs.getString(KEY_TENANT, null) ?: d.entra.tenantId,
                clientId = prefs.getString(KEY_CLIENT, null) ?: d.entra.clientId,
                redirectUri = prefs.getString(KEY_REDIRECT, null) ?: d.entra.redirectUri,
                apiScope = prefs.getString(KEY_SCOPE, null) ?: d.entra.apiScope,
            ),
        )
    }

    private fun defaults() = AppSettings(
        apiBaseUrl = BuildConfig.API_BASE_URL,
        entraEnabled = BuildConfig.ENTRA_ENABLED,
        testLogin = BuildConfig.DEV_LOGIN,
        entra = EntraSettings(BuildConfig.ENTRA_TENANT_ID, BuildConfig.ENTRA_CLIENT_ID, BuildConfig.ENTRA_REDIRECT_URI, BuildConfig.ENTRA_SCOPE),
    )

    private const val KEY_URL = "api_base_url"
    private const val KEY_ENTRA = "entra_enabled"
    private const val KEY_LOGIN = "test_login"
    private const val KEY_TENANT = "entra_tenant_id"
    private const val KEY_CLIENT = "entra_client_id"
    private const val KEY_REDIRECT = "entra_redirect_uri"
    private const val KEY_SCOPE = "entra_api_scope"
}
