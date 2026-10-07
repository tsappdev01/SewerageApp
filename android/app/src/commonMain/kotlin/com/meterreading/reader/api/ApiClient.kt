package com.meterreading.reader.api

import com.meterreading.reader.platform.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.IOException
import kotlin.concurrent.Volatile

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
 * Calls the Meter Reading API (api/README.md), from Android (OkHttp) and iOS (NSURLSession) alike.
 * Network failures surface as [IOException], server refusals as [ApiException].
 */
class ApiClient(
    baseUrl: String,
    private val http: HttpClient = createHttpClient(),
) : com.meterreading.reader.data.InspectionApi {
    private val base = baseUrl.trimEnd('/')

    /** The server this client talks to, without a trailing slash. */
    override val baseUrl: String get() = base
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * The reader this phone belongs to (Settings). With [device] set it goes as X-Reader next to the
     * phone's key; without, as X-Dev-User, which only an API in UAT mode accepts.
     */
    @Volatile
    override var devUser: String? = null

    /** This phone's registration (FR-002); its key goes with every call. */
    @Volatile
    var device: DeviceCredentials? = null

    /** Exchanges a one-time code from IT for this phone's id and secret key (POST /devices/register). */
    suspend fun registerDevice(code: String, model: String?, androidVersion: String?, appVersion: String?): DeviceCredentials {
        val body = json.encodeToString(RegisterDeviceRequest.serializer(), RegisterDeviceRequest(code, model, androidVersion, appVersion))
        val response = send(HttpMethod.Post, "/api/v1/devices/register", { jsonBody(body) }) {
            json.decodeFromString(RegisterDeviceResponse.serializer(), it)
        }
        return DeviceCredentials(response.deviceId, response.deviceKey, response.label)
    }

    /** True when the server answers /health/live; for the Settings screen's "Test" button. No sign-in needed. */
    suspend fun isReachable(): Boolean = transport { http.get("$base/health/live").status.isSuccess() }

    suspend fun me(): MeDto = get("/api/v1/me")

    suspend fun meters(zones: List<String> = emptyList()): SyncDto =
        send(HttpMethod.Get, "/api/v1/sync/meters", { if (zones.isNotEmpty()) parameter("zone", zones.joinToString(",")) }) {
            json.decodeFromString(SyncDto.serializer(), it)
        }

    suspend fun myReadings(): List<ReadingDto> = get("/api/v1/readings/mine")

    suspend fun submit(request: SubmitReadingRequest): SubmitReadingResponse =
        send(HttpMethod.Post, "/api/v1/readings", { jsonBody(json.encodeToString(SubmitReadingRequest.serializer(), request)) }) {
            json.decodeFromString(SubmitReadingResponse.serializer(), it)
        }

    /** One photo of a stored reading (FR-009). The server checks the bytes against [sha256Hex]. */
    suspend fun uploadPhoto(
        transactionId: String,
        imageId: String,
        role: String,
        capturedAtUtc: String,
        bytes: ByteArray,
        sha256Hex: String,
    ): ImageUploadResponse =
        send(HttpMethod.Put, "/api/v1/readings/$transactionId/images/$imageId", {
            parameter("role", role)
            parameter("capturedAtUtc", capturedAtUtc)
            jpegBody(bytes, sha256Hex)
        }) { json.decodeFromString(ImageUploadResponse.serializer(), it) }

    // --- Field Inspection (spec §21) ---

    override suspend fun inspectionPlan(): InspectionPlanListDto = get("/api/v1/inspections/plan")

    override suspend fun inspectionUnits(periodCode: String, propertyCode: String, tenantCode: String): InspectionUnitsDto =
        send(HttpMethod.Get, "/api/v1/inspections/units", {
            parameter("period", periodCode)
            parameter("property", propertyCode)
            parameter("tenant", tenantCode)
        }) { json.decodeFromString(InspectionUnitsDto.serializer(), it) }

    override suspend fun submitInspection(request: SubmitInspectionRequest): SubmitInspectionResponse =
        send(HttpMethod.Post, "/api/v1/inspections", { jsonBody(json.encodeToString(SubmitInspectionRequest.serializer(), request)) }) {
            json.decodeFromString(SubmitInspectionResponse.serializer(), it)
        }

    /** One evidence photo of a unit ([resultId]) or the visit's signature ([resultId] null). */
    override suspend fun uploadInspectionPhoto(
        visitId: String,
        imageId: String,
        resultId: String?,
        capturedAtUtc: String,
        bytes: ByteArray,
        sha256Hex: String,
    ): InspectionImageResponse =
        send(HttpMethod.Put, "/api/v1/inspections/$visitId/images/$imageId", {
            parameter("role", if (resultId == null) "SIGNATURE" else "EVIDENCE")
            if (resultId != null) parameter("result", resultId)
            parameter("capturedAtUtc", capturedAtUtc)
            jpegBody(bytes, sha256Hex)
        }) { json.decodeFromString(InspectionImageResponse.serializer(), it) }

    private suspend inline fun <reified T> get(path: String): T =
        send(HttpMethod.Get, path, {}) { json.decodeFromString<T>(it) }

    private fun HttpRequestBuilder.jsonBody(text: String) {
        contentType(ContentType.Application.Json)
        setBody(text)
    }

    private fun HttpRequestBuilder.jpegBody(bytes: ByteArray, sha256Hex: String) {
        contentType(ContentType.Image.JPEG)
        header("X-Content-SHA256", sha256Hex)
        setBody(bytes)
    }

    private suspend fun <T> send(method: HttpMethod, path: String, configure: HttpRequestBuilder.() -> Unit, parse: (String) -> T): T {
        val (status, body) = transport {
            val response = http.request("$base$path") {
                this.method = method
                val registered = device
                if (registered != null) {
                    header("X-Device-Id", registered.deviceId)
                    header("X-Device-Key", registered.deviceKey)
                    devUser?.let { header("X-Reader", it) }
                } else {
                    devUser?.let { header("X-Dev-User", it) }
                }
                configure()
            }
            response.status.value to response.bodyAsText()
        }
        if (status !in 200..299) {
            val problem = runCatching { json.decodeFromString(ProblemDto.serializer(), body) }.getOrNull()
            throw ApiException(status, problem?.code, problem?.title ?: "The server answered $status.")
        }
        return try {
            parse(body)
        } catch (e: SerializationException) {
            // Never crash on an answer the app does not understand; show a message instead.
            throw ApiException(status, "BAD_RESPONSE", "The server's answer could not be read. Ask IT to check the app version.")
        } catch (e: IllegalArgumentException) {
            throw ApiException(status, "BAD_RESPONSE", "The server's answer could not be read. Ask IT to check the app version.")
        }
    }

    /**
     * Any failure to reach the server becomes an [IOException], whatever the platform's HTTP client
     * throws (OkHttp's are already IOExceptions; iOS's are not), so callers keep the work on the phone.
     */
    private suspend fun <T> transport(call: suspend () -> T): T = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e.message ?: "No connection", e)
    }
}
