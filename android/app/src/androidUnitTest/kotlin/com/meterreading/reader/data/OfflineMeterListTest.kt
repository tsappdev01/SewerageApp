package com.meterreading.reader.data

import com.meterreading.reader.api.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.KeyGenerator

/** FR-020.1: the meter list is kept on the phone, encrypted, so the app opens and works without signal. */
class OfflineMeterListTest {
    private val sealer = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { k -> AesGcmSealer { k } }
    private val dir: File = Files.createTempDirectory("mrl").toFile()
    private val server = MockWebServer()
    private var offline = false
    private var now = Instant.parse("2026-10-04T07:00:00Z")

    private val me = """{"readerId":"E1001","displayName":"Rashid","openPeriod":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"}}"""
    private val sync = """{"period":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"},"zones":[{"code":"598"}],
        "properties":[{"code":"1499-W1","zoneCode":"598","companyName":"Sandline Logistics","tenants":[{"code":"T-0201","companyName":"Sandline Logistics"}]}],
        "meters":[{"id":"BC0006","number":"2001-2","type":"IRRIGATION","propertyCode":"1499-W1","zoneCode":"598","registerDigits":5,"previousReading":52500.0,"state":"PENDING"},
                  {"id":"BC0007","number":"2002-2","type":"SEWERAGE","propertyCode":"1499-W1","zoneCode":"598","registerDigits":5,"previousReading":17040.0,"state":"PENDING"}],
        "serverTimeUtc":"2026-10-04T07:00:00Z"}"""

    @Before fun start() = server.start()

    @After fun stop() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun ok(body: String) = server.enqueue(MockResponse().setResponseCode(200).setBody(body))

    private fun app(): ApiMeterRepository {
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> if (offline) throw IOException("no signal") else chain.proceed(chain.request()) })
            .build()
        return ApiMeterRepository(
            ApiClient(server.url("/").toString(), http), ZoneOffset.UTC,
            store = QueueStore(File(dir, "queue.mrq"), sealer), vault = PhotoVault(sealer),
            listCache = MeterListCache(File(dir, "meters.mrq"), sealer), clock = { now },
        )
    }

    private fun signInOnline(repo: ApiMeterRepository, login: String = "rashid@dip.ae") {
        ok(me); ok(sync); ok("[]")
        assertEquals(SignInResult.Success, runBlocking { repo.signIn(login) })
        repeat(3) { server.takeRequest() }
    }

    private fun draft(meterId: String) = ReadingDraft(
        transactionId = UUID.randomUUID().toString(), meterId = meterId, condition = MeterCondition.WORKING,
        reasonCode = null, note = "", numbers = mapOf(NumberTarget.CURRENT to 52_840L), newMeterNumber = null,
        photos = emptyList(), readerConfirmedWarning = false, capturedAt = LocalDateTime.of(2026, 10, 4, 7, 15), tenantCode = "T-0201",
    )

    @Test
    fun `FR020_1 without signal the app opens from the saved list and can take readings`() {
        signInOnline(app())
        offline = true
        now = now.plus(Duration.ofHours(3))
        val restarted = app()
        assertEquals(SignInResult.Success, runBlocking { restarted.signIn("rashid@dip.ae") })
        assertEquals(listOf("BC0006", "BC0007"), restarted.meters.value.map { it.id })
        assertEquals("Rashid", restarted.readerName.value)
        assertEquals(Instant.parse("2026-10-04T07:00:00Z"), restarted.savedListFrom.value)
        assertEquals("Sandline Logistics", restarted.property("1499-W1").tenants.single().companyName) // tenant check works offline
        assertEquals(SubmitOutcome.QUEUED, runBlocking { restarted.submit(draft("BC0006")) }.outcome)
    }

    @Test
    fun `the saved list is encrypted`() {
        signInOnline(app())
        val onDisk = File(dir, "meters.mrq").readBytes().toString(Charsets.ISO_8859_1)
        listOf("BC0006", "Sandline", "rashid@dip.ae", "52500").forEach { assertFalse(it, onDisk.contains(it)) }
    }

    @Test
    fun `another reader, another server or a list older than seven days is not used`() {
        signInOnline(app())
        offline = true
        assertTrue(runBlocking { app().signIn("anil@dip.ae") } is SignInResult.Failed)
        assertNull(MeterListCache(File(dir, "meters.mrq"), sealer).load("https://other.example", "rashid@dip.ae", now))
        now = now.plus(Duration.ofDays(7))
        assertTrue(runBlocking { app().signIn("rashid@dip.ae") } is SignInResult.Failed)
    }

    @Test
    fun `a meter sent before losing signal is not offered again after a restart`() {
        val repo = app()
        signInOnline(repo)
        val d = draft("BC0007")
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"transactionId":"${d.transactionId}","meterId":"BC0007","status":"ACCEPTED","state":"SENT","exceptions":[]}"""))
        assertEquals(SubmitOutcome.SENT, runBlocking { repo.submit(d) }.outcome)

        offline = true
        val restarted = app()
        runBlocking { restarted.signIn("rashid@dip.ae") }
        assertEquals(ReadingState.SENT, restarted.meter("BC0007").state)
    }

    @Test
    fun `back online, the fresh list replaces the saved one`() {
        signInOnline(app())
        offline = true
        val repo = app()
        runBlocking { repo.signIn("rashid@dip.ae") }
        assertNotNull(repo.savedListFrom.value)
        offline = false
        ok(sync); ok("[]")
        assertTrue(runBlocking { repo.refresh() })
        assertNull(repo.savedListFrom.value)
    }
}
