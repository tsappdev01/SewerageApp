package com.meterreading.reader.data

import com.meterreading.reader.api.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** ApiMeterRepository against a scripted server: JSON mapping, sign-in errors, the offline queue. */
class ApiMeterRepositoryTest {
    private val server = MockWebServer()
    private var offline = false
    private lateinit var repo: ApiMeterRepository

    private val me = """{"readerId":"E1001","displayName":"Rashid","teamCode":"T1","openPeriod":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"}}"""
    private val sync = """
        {"period":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"},
         "zones":[{"code":"598","name":"DIP 2"}],
         "properties":[{"code":"1499-W1","name":"Building 1499-W1","zoneCode":"598","routeSequence":1}],
         "meters":[
           {"id":"BC0006","number":"2001-2","type":"IRRIGATION","propertyCode":"1499-W1","zoneCode":"598","routeSequence":2,
            "registerDigits":5,"decimalDigits":0,"previousReading":52500.0,"lastConsumption":1200.0,"isFirstReading":false,
            "averageConsumption":1000.0,"expectedHigh":3000.0,"state":"PENDING"},
           {"id":"BC0008","number":"2002-3","type":"SEWERAGE","propertyCode":"1499-W1","zoneCode":"598","routeSequence":4,
            "registerDigits":5,"decimalDigits":0,"previousReading":22310.0,"isFirstReading":false,"expectedHigh":null,
            "state":"READ_AGAIN","supervisorNote":"Photo not clear","newField":"ignored"}],
         "serverTimeUtc":"2026-10-04T07:00:00Z"}
        """.trimIndent()
    private val mine = """[{"transactionId":"0b7c0a3e-0013-4000-8000-000000000013","meterId":"BC0013","condition":"WORKING","newReading":5300.0,
        "capturedAtUtc":"2026-10-04T03:30:00Z","receivedAtUtc":"2026-10-04T03:30:02Z","status":"EXCEPTION","state":"CHECKING"}]"""

    @Before
    fun setUp() {
        server.start()
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> if (offline) throw IOException("no signal") else chain.proceed(chain.request()) })
            .build()
        repo = ApiMeterRepository(ApiClient(server.url("/").toString(), http), ZoneOffset.UTC)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueue(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private fun signIn(): SignInResult {
        enqueue(200, me); enqueue(200, sync); enqueue(200, mine)
        return runBlocking { repo.signIn("rashid@dip.example") }
    }

    private fun draft(meterId: String = "BC0006", reading: Long = 52_840) = ReadingDraft(
        transactionId = UUID.randomUUID().toString(), meterId = meterId, condition = MeterCondition.WORKING,
        reasonCode = null, note = "", numbers = mapOf(NumberTarget.CURRENT to reading), newMeterNumber = null,
        photoPaths = emptyMap(), readerConfirmedWarning = false, capturedAt = LocalDateTime.of(2026, 10, 4, 7, 15),
    )

    private fun takeRequests(n: Int): List<RecordedRequest> = List(n) { server.takeRequest() }

    @Test
    fun `sign in loads reader, meters and readings with the dev user header`() {
        assertEquals(SignInResult.Success, signIn())
        assertEquals("Rashid", repo.readerName.value)
        val meters = repo.meters.value.associateBy { it.id }
        assertEquals(52_500L, meters.getValue("BC0006").previousReading)
        assertEquals(3_000L, meters.getValue("BC0006").expectedHigh)
        assertEquals(1_200L, meters.getValue("BC0006").lastConsumption)
        assertEquals(null, meters.getValue("BC0008").expectedHigh)
        assertEquals(ReadingState.READ_AGAIN, meters.getValue("BC0008").state)
        assertEquals("Photo not clear", meters.getValue("BC0008").supervisorNote)
        assertEquals("Building 1499-W1", repo.property("1499-W1").name)
        assertEquals(ReadingState.CHECKING, repo.readings.value.single().state)
        assertTrue(takeRequests(3).all { it.getHeader("X-Dev-User") == "rashid@dip.example" })
    }

    @Test
    fun `sign in shows the server's reason when refused`() {
        enqueue(409, """{"title":"More than one active reader has this sign-in name.","status":409,"code":"LOGIN_NOT_UNIQUE"}""")
        val result = runBlocking { repo.signIn("shared@dip.example") }
        assertEquals(SignInResult.Failed("More than one active reader has this sign-in name."), result)
    }

    @Test
    fun `an answer the app cannot read is a message, not a crash`() {
        enqueue(200, """{"unexpected":true}""")
        val result = runBlocking { repo.signIn("rashid@dip.example") } as SignInResult.Failed
        assertTrue(result.message.startsWith("The server's answer could not be read"))
    }

    @Test
    fun `sign in without signal says so`() {
        offline = true
        val result = runBlocking { repo.signIn("rashid@dip.example") } as SignInResult.Failed
        assertTrue(result.message.startsWith("Cannot reach the server"))
        assertEquals(false, repo.online.value)
    }

    @Test
    fun `submit sends the reading and marks the meter done`() {
        signIn(); takeRequests(3)
        enqueue(201, """{"transactionId":"t1","meterId":"BC0006","status":"ACCEPTED","state":"SENT","consumption":340.0,"exceptions":[]}""")
        val d = draft()
        assertEquals(SubmitResult(SubmitOutcome.SENT), runBlocking { repo.submit(d) })
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"transactionId\":\"${d.transactionId}\""))
        assertTrue(body.contains("\"newReading\":52840"))
        assertTrue(body.contains("\"capturedAtUtc\":\"2026-10-04T07:15:00Z\""))
        assertEquals(ReadingState.SENT, repo.meter("BC0006").state)
    }

    @Test
    fun `an exception goes to checking`() {
        signIn(); takeRequests(3)
        enqueue(201, """{"transactionId":"t1","meterId":"BC0006","status":"EXCEPTION","state":"CHECKING","exceptions":["HIGH_CONSUMPTION"]}""")
        assertEquals(SubmitOutcome.CHECKING, runBlocking { repo.submit(draft()) }.outcome)
        assertEquals(ReadingState.CHECKING, repo.meter("BC0006").state)
    }

    @Test
    fun `a refusal is shown with the server's words and not queued`() {
        signIn(); takeRequests(3)
        enqueue(409, """{"title":"This meter was already read this period.","status":409,"code":"ALREADY_READ"}""")
        assertEquals(SubmitResult(SubmitOutcome.REJECTED, "This meter was already read this period."), runBlocking { repo.submit(draft()) })
        assertEquals(0, runBlocking { repo.sendQueued() })
    }

    @Test
    fun `no signal queues the reading and sends it later with the same transaction id`() {
        signIn(); takeRequests(3)
        offline = true
        val d = draft()
        assertEquals(SubmitOutcome.QUEUED, runBlocking { repo.submit(d) }.outcome)
        assertEquals(ReadingState.QUEUED, repo.meter("BC0006").state)
        assertEquals(ReadingState.QUEUED, repo.readings.value.first().state)

        offline = false
        enqueue(201, """{"transactionId":"${d.transactionId}","meterId":"BC0006","status":"ACCEPTED","state":"SENT","exceptions":[]}""")
        enqueue(200, sync.replace("\"state\":\"PENDING\"", "\"state\":\"SENT\"")); enqueue(200, mine)
        assertEquals(1, runBlocking { repo.sendQueued() })
        assertTrue(server.takeRequest().body.readUtf8().contains(d.transactionId))
        assertEquals(ReadingState.SENT, repo.meter("BC0006").state)
        assertEquals(true, repo.online.value)
    }

    @Test
    fun `a server error keeps the reading queued`() {
        signIn(); takeRequests(3)
        enqueue(503, "")
        assertEquals(SubmitOutcome.QUEUED, runBlocking { repo.submit(draft()) }.outcome)
    }
}
