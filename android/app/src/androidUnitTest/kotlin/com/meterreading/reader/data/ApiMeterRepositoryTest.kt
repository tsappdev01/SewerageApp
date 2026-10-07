package com.meterreading.reader.data

import com.meterreading.reader.platform.*
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
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/** ApiMeterRepository against a scripted server: JSON mapping, sign-in errors, the offline queue. */
class ApiMeterRepositoryTest {
    private val server = MockWebServer()
    private var offline = false
    private lateinit var repo: ApiMeterRepository

    private val me = """{"readerId":"E1001","displayName":"Rashid","teamCode":"T1","openPeriod":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"}}"""
    private val sync = """
        {"period":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"},
         "zones":[{"code":"598","name":"DIP 2"}],
         "properties":[{"code":"1499-W1","name":"1499-W1","zoneCode":"598","routeSequence":1,"tenantCode":"T-0201","companyName":"Sandline Logistics LLC",
                         "tenants":[{"code":"T-0201","companyName":"Sandline Logistics LLC"},{"code":"T-0209","companyName":"Sandline Cold Chain"}]}],
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
        repo = ApiMeterRepository(ApiClient(server.url("/").toString(), http), TimeZone.UTC)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueue(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private fun signIn(): SignInResult {
        enqueue(200, me); enqueue(200, sync); enqueue(200, mine)
        return runBlocking { repo.signIn("rashid@dip.example") }
    }

    private fun draft(meterId: String = "BC0006", reading: Long = 52_840, photos: List<DraftPhoto> = emptyList()) = ReadingDraft(
        transactionId = randomUuid(), meterId = meterId, condition = MeterCondition.WORKING,
        reasonCode = null, note = "", numbers = mapOf(NumberTarget.CURRENT to reading), newMeterNumber = null,
        photos = photos, readerConfirmedWarning = false, capturedAt = LocalDateTime.of(2026, 10, 4, 7, 15),
        tenantCode = "T-0201",
    )

    private fun photo(bytes: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)): DraftPhoto {
        val file = File.createTempFile("meter", ".jpg").apply { writeBytes(bytes); deleteOnExit() }
        return DraftPhoto(randomUuid(), ImageRole.DISPLAY, file.path)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    private fun stored(d: ReadingDraft) =
        """{"transactionId":"${d.transactionId}","meterId":"${d.meterId}","status":"ACCEPTED","state":"SENT","exceptions":[]}"""

    private fun imageStored(d: ReadingDraft, p: DraftPhoto) =
        """{"imageId":"${p.imageId}","transactionId":"${d.transactionId}","role":"DISPLAY","sizeBytes":6,"sha256":"x"}"""

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
        assertEquals("Sandline Logistics LLC", repo.property("1499-W1").displayName)
        assertEquals("T-0201", repo.property("1499-W1").tenantCode)
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

    @Test
    fun `FR009 photos go up after the reading with their hash and are deleted from the phone`() {
        signIn(); takeRequests(3)
        val p = photo()
        val d = draft(photos = listOf(p))
        enqueue(201, stored(d)); enqueue(201, imageStored(d, p))
        assertEquals(SubmitOutcome.SENT, runBlocking { repo.submit(d) }.outcome)

        val reading = server.takeRequest()
        assertTrue(reading.body.readUtf8().contains("\"photoCount\":1"))
        val upload = server.takeRequest()
        assertEquals("PUT", upload.method)
        assertEquals("/api/v1/readings/${d.transactionId}/images/${p.imageId}", upload.requestUrl!!.encodedPath)
        assertEquals("DISPLAY", upload.requestUrl!!.queryParameter("role"))
        assertEquals("2026-10-04T07:15:00Z", upload.requestUrl!!.queryParameter("capturedAtUtc"))
        val bytes = upload.body.readByteArray()
        assertEquals(sha256(bytes), upload.getHeader("X-Content-SHA256"))
        assertEquals("image/jpeg", upload.getHeader("Content-Type"))
        assertEquals(false, File(p.path).exists())
        assertEquals(0, repo.photosWaiting.value)
    }

    @Test
    fun `a photo that cannot be sent waits and goes up later`() {
        signIn(); takeRequests(3)
        val p = photo()
        val d = draft(photos = listOf(p))
        enqueue(201, stored(d)); enqueue(503, "")
        assertEquals(SubmitOutcome.SENT, runBlocking { repo.submit(d) }.outcome) // the reading is not held up
        takeRequests(2)
        assertEquals(1, repo.photosWaiting.value)
        assertTrue(repo.hasWaiting())
        assertTrue(File(p.path).exists())

        enqueue(201, imageStored(d, p)); enqueue(200, sync); enqueue(200, mine)
        assertEquals(1, runBlocking { repo.sendQueued() })
        assertEquals(p.imageId, server.takeRequest().requestUrl!!.pathSegments.last())
        assertEquals(0, repo.photosWaiting.value)
        assertEquals(false, File(p.path).exists())
    }

    @Test
    fun `photos of a reading saved without signal go up after the reading`() {
        signIn(); takeRequests(3)
        val p = photo()
        val d = draft(photos = listOf(p))
        offline = true
        assertEquals(SubmitOutcome.QUEUED, runBlocking { repo.submit(d) }.outcome)
        assertEquals(0, repo.photosWaiting.value) // only counted once the reading is stored

        offline = false
        enqueue(201, stored(d)); enqueue(201, imageStored(d, p)); enqueue(200, sync); enqueue(200, mine)
        assertEquals(2, runBlocking { repo.sendQueued() })
        assertEquals("POST", server.takeRequest().method)
        assertEquals("PUT", server.takeRequest().method)
        assertEquals(false, File(p.path).exists())
    }

    @Test
    fun `photos of a refused reading are removed`() {
        signIn(); takeRequests(3)
        val p = photo()
        enqueue(409, """{"title":"This meter was already read this period.","status":409,"code":"ALREADY_READ"}""")
        assertEquals(SubmitOutcome.REJECTED, runBlocking { repo.submit(draft(photos = listOf(p))) }.outcome)
        assertEquals(false, File(p.path).exists())
        assertEquals(0, repo.photosWaiting.value)
    }

    @Test
    fun `the sub-tenant name typed by the reader is sent with the reading`() {
        signIn(); takeRequests(3)
        val d = draft().copy(subTenant = "  Al Fajr Workshop ")
        enqueue(201, stored(d))
        runBlocking { repo.submit(d) }
        assertTrue(server.takeRequest().body.readUtf8().contains("\"subTenant\":\"Al Fajr Workshop\""))
    }

    @Test
    fun `FR006_12 sync gives each property its tenants`() {
        signIn()
        assertEquals(listOf("T-0201", "T-0209"), repo.property("1499-W1").tenants.map { it.code })
    }

    @Test
    fun `FR006_12 the checked tenant is sent with the reading`() {
        signIn(); takeRequests(3)
        val d = draft().copy(tenantCode = "T-0209")
        enqueue(201, stored(d))
        assertEquals(SubmitOutcome.SENT, runBlocking { repo.submit(d) }.outcome)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"tenantCode\":\"T-0209\""))
    }

    @Test
    fun `FR006_12 a reading whose tenant was not checked is neither sent nor saved`() {
        signIn(); takeRequests(3)
        offline = true // even with no signal it must not be queued
        val result = runBlocking { repo.submit(draft().copy(tenantCode = null)) }
        assertEquals(SubmitResult(SubmitOutcome.REJECTED, "Check the tenant first."), result)
        assertEquals(ReadingState.PENDING, repo.meter("BC0006").state)
        assertTrue(repo.readings.value.none { it.state == ReadingState.QUEUED })
    }

    @Test
    fun `FR006_12 a tenant from another property is refused on the phone`() {
        signIn(); takeRequests(3)
        assertEquals(SubmitOutcome.REJECTED, runBlocking { repo.submit(draft().copy(tenantCode = "T-0101")) }.outcome)
    }

    @Test
    fun `FR006_12 a queued reading refused for its tenant comes back to read again`() {
        signIn(); takeRequests(3)
        offline = true
        runBlocking { repo.submit(draft()) }
        offline = false
        enqueue(409, """{"title":"The tenant of this property has changed. Refresh and check the tenant again.","status":409,"code":"TENANT_CHANGED"}""")
        assertEquals(0, runBlocking { repo.sendQueued() })
        assertEquals(ReadingState.READ_AGAIN, repo.meter("BC0006").state)
        assertEquals("The tenant of this property has changed. Refresh and check the tenant again.", repo.meter("BC0006").supervisorNote)
    }
}
