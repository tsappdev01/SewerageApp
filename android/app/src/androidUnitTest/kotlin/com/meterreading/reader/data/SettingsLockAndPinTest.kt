package com.meterreading.reader.data

import com.meterreading.reader.platform.*
import com.meterreading.reader.api.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/** Settings rules, the phone-lock timing (FR-001.1, FR-001.5) and the supervisor PIN. */
class SettingsLockAndPinTest {
    // --- settings ---

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
    }

    @Test
    fun `settings need a server and the reader's email`() {
        assertEquals(emptyList<String>(), SettingsRules.problems(AppSettings("https://mr.example", "rashid@dip.ae"), allowHttp = false))
        assertEquals(1, SettingsRules.problems(AppSettings("https://mr.example", ""), allowHttp = false).size)
        assertEquals(2, SettingsRules.problems(AppSettings("mr.example", "rashid"), allowHttp = false).size)
        val cleaned = SettingsRules.cleaned(AppSettings(" https://mr.example ", " rashid@dip.ae "), allowHttp = false)
        assertEquals(AppSettings("https://mr.example/", "rashid@dip.ae"), cleaned)
    }

    // --- phone lock timing ---

    private val minute = 60_000L

    @Test
    fun `FR001_1 the phone lock is asked for when the app starts`() {
        assertTrue(UnlockPolicy.needsUnlock(lockOn = true, unlocked = false, backgroundSinceMillis = null, nowMillis = 0))
        assertFalse(UnlockPolicy.needsUnlock(lockOn = false, unlocked = false, backgroundSinceMillis = null, nowMillis = 0))
    }

    @Test
    fun `FR001_5 a short break keeps the app open, fifteen minutes away locks it`() {
        assertFalse(UnlockPolicy.needsUnlock(true, unlocked = true, backgroundSinceMillis = 0, nowMillis = 14 * minute))
        assertTrue(UnlockPolicy.needsUnlock(true, unlocked = true, backgroundSinceMillis = 0, nowMillis = 15 * minute))
        assertFalse(UnlockPolicy.needsUnlock(true, unlocked = true, backgroundSinceMillis = null, nowMillis = 99 * minute))
    }

    // --- supervisor PIN ---

    @Test
    fun `PIN is 4 to 8 digits`() {
        assertTrue(SupervisorPin.isValid("1234"))
        assertTrue(SupervisorPin.isValid("12345678"))
        assertFalse(SupervisorPin.isValid("123"))
        assertFalse(SupervisorPin.isValid("123456789"))
        assertFalse(SupervisorPin.isValid("12a4"))
    }

    @Test
    fun `only a salted hash is kept and the right PIN opens`() {
        val stored = SupervisorPin.create("2580")
        assertFalse(stored.hash.contains("2580"))
        assertNotEquals(stored.hash, SupervisorPin.create("2580").hash) // a new salt each time
        val r = SupervisorPin.check("2580", stored, SupervisorPin.Attempts(), nowMillis = 0)
        assertTrue(r is SupervisorPin.Result.Ok)
    }

    @Test
    fun `wrong PINs count down, then entry waits a minute`() {
        val stored = SupervisorPin.create("2580")
        var attempts = SupervisorPin.Attempts()
        repeat(SupervisorPin.MAX_TRIES - 1) { i ->
            val r = SupervisorPin.check("0000", stored, attempts, nowMillis = 0) as SupervisorPin.Result.Wrong
            assertEquals(SupervisorPin.MAX_TRIES - 1 - i, r.triesLeft)
            attempts = r.attempts
        }
        val blocked = SupervisorPin.check("0000", stored, attempts, nowMillis = 0) as SupervisorPin.Result.Blocked
        assertEquals(SupervisorPin.LOCK_SECONDS, blocked.secondsLeft)
        // Even the right PIN waits while blocked.
        assertTrue(SupervisorPin.check("2580", stored, blocked.attempts, nowMillis = 30_000) is SupervisorPin.Result.Blocked)
        // After the minute the right PIN opens and the count starts again.
        val ok = SupervisorPin.check("2580", stored, blocked.attempts, nowMillis = 60_000) as SupervisorPin.Result.Ok
        assertEquals(SupervisorPin.Attempts(), ok.attempts)
    }

    // --- the reader the phone belongs to, at the API ---

    private val server = MockWebServer()
    private val me = """{"readerId":"E1001","displayName":"Rashid","openPeriod":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"}}"""
    private val sync = """{"period":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"},"zones":[],
        "properties":[{"code":"1499-W1","zoneCode":"598","tenants":[{"code":"T-0201","companyName":"Sandline"}]}],
        "meters":[{"id":"BC0006","number":"2001-2","type":"IRRIGATION","propertyCode":"1499-W1","zoneCode":"598","registerDigits":5,"previousReading":52500.0,"state":"PENDING"}],
        "serverTimeUtc":"2026-10-04T07:00:00Z"}"""

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun ok(body: String) = server.enqueue(MockResponse().setResponseCode(200).setBody(body))

    @Test
    fun `the phone's reader is sent with each call`() {
        val repo = ApiMeterRepository(ApiClient(server.url("/").toString()), TimeZone.UTC)
        ok(me); ok(sync); ok("[]")
        runBlocking { repo.signIn("rashid@dip.ae") }
        repeat(3) { assertEquals("rashid@dip.ae", server.takeRequest().getHeader("X-Dev-User")) }
    }

    @Test
    fun `a reader the server does not accept keeps the reading on the phone`() {
        val repo = ApiMeterRepository(ApiClient(server.url("/").toString()), TimeZone.UTC)
        ok(me); ok(sync); ok("[]")
        runBlocking { repo.signIn("rashid@dip.ae") }
        server.enqueue(MockResponse().setResponseCode(401).setBody(""))
        val draft = ReadingDraft(
            transactionId = randomUuid(), meterId = "BC0006", condition = MeterCondition.WORKING,
            reasonCode = null, note = "", numbers = mapOf(NumberTarget.CURRENT to 52_840L), newMeterNumber = null,
            photos = emptyList(), readerConfirmedWarning = false, capturedAt = LocalDateTime.of(2026, 10, 4, 7, 15), tenantCode = "T-0201",
        )
        assertEquals(SubmitOutcome.QUEUED, runBlocking { repo.submit(draft) }.outcome)
        assertTrue(repo.signInNeeded.value)
        assertTrue(repo.hasWaiting())
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
