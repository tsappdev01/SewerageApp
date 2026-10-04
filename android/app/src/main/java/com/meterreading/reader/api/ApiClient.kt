package com.meterreading.reader.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The server refused the request. [code] is the problem code from spec Appendix A. */
class ApiException(val status: Int, val code: String?, val title: String) : Exception("$status $code: $title") {
    /** 408, 429 and 5xx are worth retrying later; other refusals are final. */
    val isRetryable: Boolean get() = status == 408 || status == 429 || status >= 500

    /**
     * The server did not accept this phone or reader (not registered, blocked, or not signed in):
     * keep the reader's work on the phone and go back to the start screen.
     */
    val needsSignIn: Boolean get() = status == 401 || code == "DEVICE_NOT_REGISTERED" || code == "DEVICE_REVOKED"
}


/**
 * Calls the Meter Reading API (api/README.md). Network failures surface as [IOException],
 * server refusals as [ApiException].
 */
class ApiClient(
    baseUrl: String,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    private val base = baseUrl.trimEnd('/')

    /** The server this client talks to, without a trailing slash. */
    val baseUrl: String get() = base
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * The reader this phone belongs to (Settings). With [device] set it goes as X-Reader next to the
     * phone's key; without, as X-Dev-User, which only an API in UAT mode accepts.
     */
    @Volatile
    var devUser: String? = null

    /** This phone's registration (FR-002); its key goes with every call. */
    @Volatile
    var device: DeviceCredentials? = null

    /** Exchanges a one-time code from IT for this phone's id and secret key (POST /devices/register). */
    suspend fun registerDevice(code: String, model: String?, androidVersion: String?, appVersion: String?): DeviceCredentials {
        val body = json.encodeToString(RegisterDeviceRequest.serializer(), RegisterDeviceRequest(code, model, androidVersion, appVersion))
        val response = send(Request.Builder().url("$base/api/v1/devices/register").post(body.toRequestBody(JSON))) {
            json.decodeFromString(RegisterDeviceResponse.serializer(), it)
        }
        return DeviceCredentials(response.deviceId, response.deviceKey, response.label)
    }

    /** True when the server answers /health/live; for the Settings screen's "Test" button. No sign-in needed. */
    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$base/health/live".toHttpUrl()).get().build()).execute().use { it.isSuccessful }
    }

    suspend fun me(): MeDto = get("/api/v1/me")

    suspend fun meters(zones: List<String> = emptyList()): SyncDto =
        get(if (zones.isEmpty()) "/api/v1/sync/meters" else "/api/v1/sync/meters?zone=" + zones.joinToString(","))

    suspend fun myReadings(): List<ReadingDto> = get("/api/v1/readings/mine")

    suspend fun submit(request: SubmitReadingRequest): SubmitReadingResponse =
        send(
            Request.Builder()
                .url("$base/api/v1/readings")
                .post(json.encodeToString(SubmitReadingRequest.serializer(), request).toRequestBody(JSON)),
        ) { json.decodeFromString(SubmitReadingResponse.serializer(), it) }

    /** One photo of a stored reading (FR-009). The server checks the bytes against [sha256Hex]. */
    suspend fun uploadPhoto(
        transactionId: String,
        imageId: String,
        role: String,
        capturedAtUtc: String,
        bytes: ByteArray,
        sha256Hex: String,
    ): ImageUploadResponse {
        val url = "$base/api/v1/readings/$transactionId/images/$imageId".toHttpUrl().newBuilder()
            .addQueryParameter("role", role)
            .addQueryParameter("capturedAtUtc", capturedAtUtc)
            .build()
        return send(
            Request.Builder().url(url).put(bytes.toRequestBody(JPEG)).header("X-Content-SHA256", sha256Hex),
        ) { json.decodeFromString(ImageUploadResponse.serializer(), it) }
    }

    private suspend inline fun <reified T> get(path: String): T =
        send(Request.Builder().url("$base$path".toHttpUrl()).get()) { json.decodeFromString<T>(it) }

    private suspend fun <T> send(builder: Request.Builder, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val registered = device
        if (registered != null) {
            builder.header("X-Device-Id", registered.deviceId).header("X-Device-Key", registered.deviceKey)
            devUser?.let { builder.header("X-Reader", it) }
        } else {
            devUser?.let { builder.header("X-Dev-User", it) }
        }
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val problem = runCatching { json.decodeFromString(ProblemDto.serializer(), body) }.getOrNull()
                throw ApiException(response.code, problem?.code, problem?.title ?: "The server answered ${response.code}.")
            }
            try {
                parse(body)
            } catch (e: SerializationException) {
                // Never crash on an answer the app does not understand; show a message instead.
                throw ApiException(response.code, "BAD_RESPONSE", "The server's answer could not be read. Ask IT to check the app version.")
            } catch (e: IllegalArgumentException) {
                throw ApiException(response.code, "BAD_RESPONSE", "The server's answer could not be read. Ask IT to check the app version.")
            }
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val JPEG = "image/jpeg".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
