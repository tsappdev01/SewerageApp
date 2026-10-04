package com.meterreading.reader.data

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What the Settings screen changes (gear icon): the server address and how readers sign in.
 * Saved on the phone; the build only supplies the first values.
 */
data class AppSettings(
    /** The Meter Reading API, e.g. https://meterreading-api.example/ (always ends with "/"). */
    val apiBaseUrl: String,
    /** Company sign-in with Microsoft Entra ID (FR-001). Off: the test sign-in name below is used. */
    val entraEnabled: Boolean = false,
    /** Test sign-in: the reader's LoginEmail in vw_MR_Reader. Only accepted by an API in UAT mode. */
    val testLogin: String = "",
    val entra: EntraSettings = EntraSettings(),
)

/** The phone app's Entra ID app registration (docs/deployment.md). None of these are secrets. */
data class EntraSettings(
    /** Directory (tenant) ID, or the tenant's domain, e.g. dubaiinvestments.onmicrosoft.com. */
    val tenantId: String = "",
    /** The phone app's Application (client) ID. */
    val clientId: String = "",
    /** msauth://com.meterreading.reader/<signature hash>, as registered for the app. */
    val redirectUri: String = "",
    /** The API's scope, e.g. api://meterreading-api/access_as_user. */
    val apiScope: String = "",
) {
    /**
     * MSAL's configuration file for these values: one work account per phone, this tenant only, and
     * the Microsoft Authenticator / Company Portal broker when installed (needed by Intune policies).
     */
    fun toMsalConfigJson(): String = buildJsonObject {
        put("client_id", clientId)
        put("redirect_uri", redirectUri)
        put("broker_redirect_uri_registered", true)
        put("account_mode", "SINGLE")
        put("authorization_user_agent", "DEFAULT")
        putJsonArray("authorities") {
            addJsonObject {
                put("type", "AAD")
                put("default", true)
                putJsonObject("audience") {
                    put("type", "AzureADMyOrg")
                    put("tenant_id", tenantId)
                }
            }
        }
    }.toString()
}

object SettingsRules {
    private val guid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val domain = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$")

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
        if (s.entraEnabled) {
            val e = s.entra
            if (!guid.matches(e.tenantId.trim()) && !domain.matches(e.tenantId.trim())) add("Tenant ID is not right")
            if (!guid.matches(e.clientId.trim())) add("App (client) ID is not right")
            if (!e.redirectUri.trim().startsWith("msauth://")) add("Redirect URI must start with msauth://")
            if (e.apiScope.isBlank()) add("API scope is needed")
        }
    }

    /** Trimmed values with the normalized address; call after [problems] is empty. */
    fun cleaned(s: AppSettings, allowHttp: Boolean): AppSettings = s.copy(
        apiBaseUrl = normalizeUrl(s.apiBaseUrl, allowHttp) ?: s.apiBaseUrl.trim(),
        testLogin = s.testLogin.trim(),
        entra = EntraSettings(s.entra.tenantId.trim(), s.entra.clientId.trim(), s.entra.redirectUri.trim(), s.entra.apiScope.trim()),
    )
}
