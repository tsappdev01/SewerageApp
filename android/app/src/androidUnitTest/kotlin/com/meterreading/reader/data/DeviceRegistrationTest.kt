package com.meterreading.reader.data

import com.meterreading.reader.platform.*
import com.meterreading.reader.api.ApiClient
import com.meterreading.reader.api.ApiException
import com.meterreading.reader.api.DeviceCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/** Registered phones (spec FR-002): registration, the key on every call, and a blocked phone. */
class DeviceRegistrationTest {
    private val server = MockWebServer()
    private val device = DeviceCredentials("6f1c2b4a-1111-4222-8333-444455556666", "secret-key-abc", "Phone 01")
    private val me = """{"readerId":"E1001","displayName":"Rashid","openPeriod":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"}}"""
    private val sync = """{"period":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"},"zones":[],
        "properties":[{"code":"1499-W1","zoneCode":"598","tenants":[{"code":"T-0201","companyName":"Sandline"}]}],
        "meters":[{"id":"BC0006","number":"2001-2","type":"IRRIGATION","propertyCode":"1499-W1","zoneCode":"598","registerDigits":5,"previousReading":52500.0,"state":"PENDING"}],
        "serverTimeUtc":"2026-10-04T07:00:00Z"}"""

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun ok(body: String) = server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    private fun client() = ApiClient(server.url("/").toString())

    @Test
    fun `FR002_1 a code is exchanged for the phone's id and key`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"deviceId":"${device.deviceId}","deviceKey":"${device.deviceKey}","label":"Phone 01"}"""))
        val result = runBlocking { client().registerDevice("ABCD-EFGH-JKLM", "Samsung A15", "14", "0.3.0") }
        assertEquals(device, result)
        val request = server.takeRequest()
        assertEquals("/api/v1/devices/register", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"code\":\"ABCD-EFGH-JKLM\""))
        assertTrue(body.contains("\"model\":\"Samsung A15\""))
    }

    @Test
    fun `a wrong code shows the server's words`() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"title":"This code is wrong, already used or out of date. Ask IT for a new one.","status":422,"code":"REGISTRATION_CODE_INVALID"}"""))
        try {
            runBlocking { client().registerDevice("ZZZZ-ZZZZ-ZZZZ", null, null, null) }
            fail("expected a refusal")
        } catch (e: ApiException) {
            assertEquals("REGISTRATION_CODE_INVALID", e.code)
        }
    }

    @Test
    fun `FR002_6 a registered phone sends its id and key with the reader, not the test header`() {
        val repo = ApiMeterRepository(client().apply { this.device = this@DeviceRegistrationTest.device }, TimeZone.UTC)
        ok(me); ok(sync); ok("[]")
        runBlocking { repo.signIn("rashid@dip.ae") }
        val first = server.takeRequest()
        assertEquals(device.deviceId, first.getHeader("X-Device-Id"))
        assertEquals(device.deviceKey, first.getHeader("X-Device-Key"))
        assertEquals("rashid@dip.ae", first.getHeader("X-Reader"))
        assertNull(first.getHeader("X-Dev-User"))
    }

    @Test
    fun `FR002_3 a blocked phone keeps its readings and says why`() {
        val repo = ApiMeterRepository(client().apply { this.device = this@DeviceRegistrationTest.device }, TimeZone.UTC)
        ok(me); ok(sync); ok("[]")
        runBlocking { repo.signIn("rashid@dip.ae") }
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"title":"This phone has been blocked. Give it to your supervisor. Readings on it are kept.","status":403,"code":"DEVICE_REVOKED"}"""))
        val draft = ReadingDraft(
            transactionId = randomUuid(), meterId = "BC0006", condition = MeterCondition.WORKING,
            reasonCode = null, note = "", numbers = mapOf(NumberTarget.CURRENT to 52_840L), newMeterNumber = null,
            photos = emptyList(), readerConfirmedWarning = false, capturedAt = LocalDateTime.of(2026, 10, 4, 7, 15), tenantCode = "T-0201",
        )
        assertEquals(SubmitOutcome.QUEUED, runBlocking { repo.submit(draft) }.outcome)
        assertTrue(repo.signInNeeded.value)
        assertEquals("This phone has been blocked. Give it to your supervisor. Readings on it are kept.", repo.signInReason)
        assertTrue(repo.hasWaiting())
    }
}
