package com.meterreading.reader.data

import com.meterreading.reader.api.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.KeyGenerator

/** The encrypted offline queue (spec §9, FR-020, FR-008.6): it survives a restart and nothing on disk is readable. */
class OfflineQueueTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val sealer = AesGcmSealer { key }
    private val dir: File = Files.createTempDirectory("mrq").toFile()
    private val queueFile = File(dir, "queue.mrq")
    private val server = MockWebServer()
    private var offline = false

    private val me = """{"readerId":"E1001","displayName":"Rashid","openPeriod":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"}}"""
    private val sync = """{"period":{"code":"2026-10","startDate":"2026-10-01","endDate":"2026-10-31","status":"OPEN"},"zones":[],
        "properties":[{"code":"1499-W1","zoneCode":"598","tenants":[{"code":"T-0201","companyName":"Sandline"}]}],
        "meters":[{"id":"BC0006","number":"2001-2","type":"IRRIGATION","propertyCode":"1499-W1","zoneCode":"598","registerDigits":5,"previousReading":52500.0,"state":"PENDING"}],
        "serverTimeUtc":"2026-10-04T07:00:00Z"}"""

    @Before fun start() = server.start()

    @After fun stop() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun ok(body: String) = server.enqueue(MockResponse().setResponseCode(200).setBody(body))

    /** A new repository on the same files: what happens when the app is closed and opened again. */
    private fun app(onWaiting: () -> Unit = {}): ApiMeterRepository {
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> if (offline) throw IOException("no signal") else chain.proceed(chain.request()) })
            .build()
        return ApiMeterRepository(
            ApiClient(server.url("/").toString(), http), ZoneOffset.UTC,
            store = QueueStore(queueFile, sealer), vault = PhotoVault(sealer), onWaiting = onWaiting,
        )
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "meter photo 52840".toByteArray()

    private fun draft(): ReadingDraft {
        val photo = File(dir, "${UUID.randomUUID()}.jpg").apply { writeBytes(jpeg) }
        return ReadingDraft(
            transactionId = UUID.randomUUID().toString(), meterId = "BC0006", condition = MeterCondition.WORKING,
            reasonCode = null, note = "Cover broken", numbers = mapOf(NumberTarget.CURRENT to 52_840L), newMeterNumber = null,
            photos = listOf(DraftPhoto(UUID.randomUUID().toString(), ImageRole.DISPLAY, photo.path)), readerConfirmedWarning = false,
            capturedAt = LocalDateTime.of(2026, 10, 4, 7, 15), subTenant = "Al Fajr", tenantCode = "T-0201",
        )
    }

    @Test
    fun `sealed data opens again, and any change to it is detected`() {
        val sealed = sealer.seal("52840".toByteArray())
        assertTrue(sealer.isSealed(sealed))
        assertEquals("52840", sealer.open(sealed).toString(Charsets.UTF_8))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertTrue(runCatching { sealer.open(sealed) }.isFailure)
    }

    @Test
    fun `FR020 a reading saved without signal survives closing the app and goes up later with the same id`() {
        var asked = 0
        val first = app { asked++ }
        ok(me); ok(sync); ok("[]")
        runBlocking { first.signIn("rashid@dip.ae") }
        repeat(3) { server.takeRequest() }

        offline = true
        val d = draft()
        assertEquals(SubmitOutcome.QUEUED, runBlocking { first.submit(d) }.outcome)
        assertTrue(asked > 0) // a background send was requested

        // The app is closed. Nothing on disk can be read: not the meter, the reading, the note or the photo.
        val onDisk = queueFile.readBytes().toString(Charsets.ISO_8859_1)
        listOf("BC0006", "52840", "Cover broken", "T-0201", "Al Fajr").forEach { assertFalse(it, onDisk.contains(it)) }
        val photoOnDisk = File(d.photos.single().path).readBytes()
        assertFalse(photoOnDisk.copyOfRange(0, 2).contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))

        // Opened again, still waiting.
        offline = false
        val second = app()
        assertTrue(second.hasWaiting())
        assertEquals(ReadingState.QUEUED, second.readings.value.single().state)

        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"transactionId":"${d.transactionId}","meterId":"BC0006","status":"ACCEPTED","state":"SENT","exceptions":[]}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"imageId":"${d.photos.single().imageId}","transactionId":"${d.transactionId}","role":"DISPLAY","sizeBytes":${jpeg.size},"sha256":"x"}"""))
        ok(sync); ok("[]")
        assertEquals(2, runBlocking { second.sendQueued() })

        val reading = server.takeRequest()
        assertTrue(reading.body.readUtf8().contains(d.transactionId))
        val photo = server.takeRequest()
        // The server gets the real photo, with its real fingerprint.
        assertArrayEquals(jpeg, photo.body.readByteArray())
        val sha = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02X".format(it) }
        assertEquals(sha, photo.getHeader("X-Content-SHA256"))

        // Sent: nothing waits, also after another restart, and the photo is gone from the phone.
        assertFalse(second.hasWaiting())
        assertFalse(app().hasWaiting())
        assertFalse(File(d.photos.single().path).exists())
    }

    @Test
    fun `a queue file that cannot be opened is kept aside, not lost and not a crash`() {
        queueFile.writeBytes(sealer.seal("{\"readings\":[".toByteArray()))
        val store = QueueStore(queueFile, sealer)
        assertTrue(store.load().readings.isEmpty())
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("queue.mrq.unreadable-") })
    }

    @Test
    fun `photos sent at once are sealed on disk until they go up`() {
        val repo = app()
        ok(me); ok(sync); ok("[]")
        runBlocking { repo.signIn("rashid@dip.ae") }
        repeat(3) { server.takeRequest() }
        val d = draft()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"transactionId":"${d.transactionId}","meterId":"BC0006","status":"ACCEPTED","state":"SENT","exceptions":[]}"""))
        server.enqueue(MockResponse().setResponseCode(503)) // photo upload fails for now
        assertEquals(SubmitOutcome.SENT, runBlocking { repo.submit(d) }.outcome)
        assertTrue(sealer.isSealed(File(d.photos.single().path).readBytes()))
        assertEquals(1, app().photosWaiting.value) // still waiting after a restart
    }
}
