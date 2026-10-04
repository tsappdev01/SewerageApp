package com.meterreading.reader.data

import com.meterreading.reader.api.ApiClient
import com.meterreading.reader.api.SignInRequiredException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Settings screen rules, the MSAL configuration, and company sign-in tokens in the API client (FR-001). */
class SettingsAndSignInTest {
    private val entra = EntraSettings(
        tenantId = "3f1c2b4a-1111-4222-8333-444455556666",
        clientId = "9a8b7c6d-aaaa-4bbb-8ccc-ddddeeeeffff",
        redirectUri = "msauth://com.meterreading.reader/abcDEF123%3D",
        apiScope = "api://meterreading-api/access_as_user",
    )

    @Test
    fun `server address is trimmed and always ends with a slash`() {
        assertEquals("https://mr.example/", SettingsRules.normalizeUrl("  https://mr.example  ", allowHttp = false))
        assertEquals("https://mr.example/api/", SettingsRules.normalizeUrl("https://mr.example/api//", allowHttp = false))
        assertEquals("https://mr.example:8443/", SettingsRules.normalizeUrl("https://mr.example:8443", allowHttp = false))
    }

    @Test
    fun `plain http only in test builds, and nonsense is refused`() {
        assertNull(SettingsRules.normalizeUrl("http://10.0.2.2:5080", allowHttp = false))
        assertEquals("http://10.0.2.2:5080/", SettingsRules.normalizeUrl("http://10.0.2.2:5080", allowHttp = true))
        assertNull(SettingsRules.normalizeUrl("mr.example", allowHttp = true))
        assertNull(SettingsRules.normalizeUrl("https://", allowHttp = true))
        assertNull(SettingsRules.normalizeUrl("ftp://mr.example", allowHttp = true))
    }

    @Test
    fun `company sign-in off needs only a server address`() {
        assertEquals(emptyList<String>(), SettingsRules.problems(AppSettings("https://mr.example"), allowHttp = false))
        assertEquals(1, SettingsRules.problems(AppSettings("mr.example"), allowHttp = false).size)
    }

    @Test
    fun `company sign-in on checks each Entra value`() {
        val on = AppSettings("https://mr.example", entraEnabled = true, entra = entra)
        assertEquals(emptyList<String>(), SettingsRules.problems(on, allowHttp = false))
        assertEquals(emptyList<String>(), SettingsRules.problems(on.copy(entra = entra.copy(tenantId = "dubaiinvestments.onmicrosoft.com")), allowHttp = false))
        val bad = on.copy(entra = EntraSettings(tenantId = "x", clientId = "not-a-guid", redirectUri = "https://x", apiScope = " "))
        assertEquals(4, SettingsRules.problems(bad, allowHttp = false).size)
    }

    @Test
    fun `cleaned settings are trimmed`() {
        val s = SettingsRules.cleaned(AppSettings(" https://mr.example ", testLogin = " a@b.c ", entra = entra.copy(clientId = " ${entra.clientId} ")), allowHttp = false)
        assertEquals("https://mr.example/", s.apiBaseUrl)
        assertEquals("a@b.c", s.testLogin)
        assertEquals(entra.clientId, s.entra.clientId)
    }

    @Test
    fun `MSAL configuration is single account, this tenant, with the broker`() {
        val json = Json.parseToJsonElement(entra.toMsalConfigJson()).jsonObject
        assertEquals(entra.clientId, json["client_id"]!!.jsonPrimitive.content)
        assertEquals(entra.redirectUri, json["redirect_uri"]!!.jsonPrimitive.content)
        assertEquals("SINGLE", json["account_mode"]!!.jsonPrimitive.content)
        assertTrue(json["broker_redirect_uri_registered"]!!.jsonPrimitive.boolean)
        val authority = json["authorities"]!!.jsonArray.single().jsonObject
        assertEquals("AAD", authority["type"]!!.jsonPrimitive.content)
        assertEquals("AzureADMyOrg", authority["audience"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(entra.tenantId, authority["audience"]!!.jsonObject["tenant_id"]!!.jsonPrimitive.content)
    }

    // --- tokens in the API client ---

    private val server = MockWebServer()
    private val me = """{"readerId":"E1001","displayName":"Rashid","openPeriod":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"}}"""
    private val sync = """{"period":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"},"zones":[],
        "properties":[{"code":"1499-W1","zoneCode":"598","tenants":[{"code":"T-0201","companyName":"Sandline"}]}],
        "meters":[{"id":"BC0006","number":"2001-2","type":"IRRIGATION","propertyCode":"1499-W1","zoneCode":"598","registerDigits":5,"previousReading":52500.0,"state":"PENDING"}],
        "serverTimeUtc":"2026-10-04T07:00:00Z"}"""

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun ok(body: String) = server.enqueue(MockResponse().setResponseCode(200).setBody(body))

    private fun draft() = ReadingDraft(
        transactionId = UUID.randomUUID().toString(), meterId = "BC0006", condition = MeterCondition.WORKING,
        reasonCode = null, note = "", numbers = mapOf(NumberTarget.CURRENT to 52_840L), newMeterNumber = null,
        photos = emptyList(), readerConfirmedWarning = false, capturedAt = LocalDateTime.of(2026, 10, 4, 7, 15), tenantCode = "T-0201",
    )

    @Test
    fun `FR001 company sign-in sends a fresh token on each call and never the test name`() {
        var issued = 0
        val api = ApiClient(server.url("/").toString()).apply { tokenSource = { "token-${++issued}" } }
        val repo = ApiMeterRepository(api, ZoneOffset.UTC)
        assertFalse(repo.needsDevLogin)
        ok(me); ok(sync); ok("[]")
        runBlocking { repo.signIn("rashid@dip.ae") }
        val first = server.takeRequest()
        assertEquals("Bearer token-1", first.getHeader("Authorization"))
        assertNull(first.getHeader("X-Dev-User"))
        assertEquals("Bearer token-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `test sign-in sends the name and no token`() {
        val repo = ApiMeterRepository(ApiClient(server.url("/").toString()), ZoneOffset.UTC)
        assertTrue(repo.needsDevLogin)
        ok(me); ok(sync); ok("[]")
        runBlocking { repo.signIn("rashid@dip.example") }
        val first = server.takeRequest()
        assertEquals("rashid@dip.example", first.getHeader("X-Dev-User"))
        assertNull(first.getHeader("Authorization"))
    }

    @Test
    fun `FR001 when sign-in runs out the reading stays on the phone and goes up after signing in again`() {
        var expired = false
        val api = ApiClient(server.url("/").toString()).apply {
            tokenSource = { if (expired) throw SignInRequiredException("Sign in again.") else "t" }
        }
        val repo = ApiMeterRepository(api, ZoneOffset.UTC)
        ok(me); ok(sync); ok("[]")
        runBlocking { repo.signIn("rashid@dip.ae") }
        repeat(3) { server.takeRequest() }

        expired = true
        val d = draft()
        assertEquals(SubmitOutcome.QUEUED, runBlocking { repo.submit(d) }.outcome)
        assertTrue(repo.signInNeeded.value)
        assertEquals(0, server.requestCount - 3) // nothing was sent without a token
        assertEquals(0, runBlocking { repo.sendQueued() })
        assertTrue(repo.hasWaiting())

        expired = false
        ok(me); ok(sync); ok("[]")
        runBlocking { repo.signIn("rashid@dip.ae") }
        assertFalse(repo.signInNeeded.value)
        repeat(3) { server.takeRequest() }
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"transactionId":"${d.transactionId}","meterId":"BC0006","status":"ACCEPTED","state":"SENT","exceptions":[]}"""))
        ok(sync); ok("[]")
        assertEquals(1, runBlocking { repo.sendQueued() })
        assertTrue(server.takeRequest().body.readUtf8().contains(d.transactionId))
    }

    @Test
    fun `a 401 from the server also asks for sign-in and keeps the reading`() {
        val api = ApiClient(server.url("/").toString()).apply { tokenSource = { "old" } }
        val repo = ApiMeterRepository(api, ZoneOffset.UTC)
        ok(me); ok(sync); ok("[]")
        runBlocking { repo.signIn("rashid@dip.ae") }
        server.enqueue(MockResponse().setResponseCode(401).setBody(""))
        assertEquals(SubmitOutcome.QUEUED, runBlocking { repo.submit(draft()) }.outcome)
        assertTrue(repo.signInNeeded.value)
        assertEquals(ReadingState.QUEUED, repo.meter("BC0006").state)
    }

    @Test
    fun `the server test needs no sign-in`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"live"}"""))
        assertTrue(runBlocking { ApiClient(server.url("/").toString()).isReachable() })
        assertEquals("/health/live", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(runBlocking { ApiClient(server.url("/").toString()).isReachable() })
    }
}
