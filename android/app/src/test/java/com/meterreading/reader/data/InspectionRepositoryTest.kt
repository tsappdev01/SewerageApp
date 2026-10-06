package com.meterreading.reader.data

import com.meterreading.reader.api.ApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import javax.crypto.KeyGenerator

/** Field inspection on the phone against a scripted server: plan, units, sending, and working without signal (FR-036). */
class InspectionRepositoryTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val sealer = AesGcmSealer { key }
    private val dir: File = Files.createTempDirectory("insp").toFile()
    private val storeFile = File(dir, "inspections.mrq")
    private val server = MockWebServer()
    private var offline = false

    private val plan = """{"today":"2026-10-06","from":"2026-08-07","to":"2026-10-20","serverTimeUtc":"2026-10-06T05:00:00Z","plans":[
        {"periodCode":"2026-10","planDate":"2026-10-06","propertyCode":"597-559","tenantCode":"T-0559","companyName":"Elegant Industries LLC",
         "activeUnits":2,"inactiveUnits":0,"totalUnits":2,"state":"NOT_STARTED","checkedUnits":0,"flaggedUnits":0}]}"""
    private val units = """{"plan":{"periodCode":"2026-10","planDate":"2026-10-06","propertyCode":"597-559","tenantCode":"T-0559","activeUnits":2,"state":"NOT_STARTED"},
        "units":[{"unitId":"1001","buildingName":"ELEGANT","unitCode":"1","category":"Industrial>Warehouse>Warehouse","subTenantName":"GURCOAT","active":true},
                 {"unitId":"1002","buildingName":"ELEGANT","unitCode":"2","active":true}]}"""

    @Before fun start() = server.start()

    @After fun stop() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun ok(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    /** A new repository on the same files: the app closed and opened again. */
    private fun app(): InspectionRepository {
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> if (offline) throw IOException("no signal") else chain.proceed(chain.request()) })
            .build()
        val client = ApiClient(server.url("/").toString(), http).apply { devUser = "rashid@dip.ae" }
        return InspectionRepository(client, InspectionStore(storeFile, sealer), PhotoVault(sealer))
    }

    private fun photo(name: String): EvidencePhoto {
        val f = File(dir, name)
        f.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + name.toByteArray())
        return EvidencePhoto("img-$name", f.path, "2026-10-06T05:10:00Z")
    }

    private fun startVisit(repo: InspectionRepository): String = runBlocking {
        ok(plan)
        assertTrue(repo.refreshPlan())
        ok(units)
        val p = repo.plans.value.single()
        assertNotNull(repo.units(p))
        repo.startVisit(p)
        p.id
    }

    @Test fun the_plan_and_units_load_and_inspection_shows_as_available() = runBlocking {
        val repo = app()
        val id = startVisit(repo)
        assertEquals(true, repo.available.value)
        assertEquals("/api/v1/inspections/plan", server.takeRequest().path)
        assertEquals("/api/v1/inspections/units?period=2026-10&property=597-559&tenant=T-0559", server.takeRequest().path)
        assertEquals(2, repo.cachedUnits(id)!!.units.size)
    }

    @Test fun a_server_without_inspection_hides_it() = runBlocking {
        val repo = app()
        ok("""{"title":"Field inspection is not set up on this server.","status":404,"code":"INSPECTION_OFF"}""", 404)
        assertFalse(repo.refreshPlan())
        assertEquals(false, repo.available.value)
    }

    @Test fun FR036_a_visit_is_sent_then_its_photos_one_by_one() = runBlocking {
        val repo = app()
        val id = startVisit(repo)
        server.takeRequest(); server.takeRequest()
        val evidence = photo("u2.jpg")
        repo.saveEntry(id, UnitEntry("res-1", "1001", "1", result = UnitResult.AS_RECORDED, peopleSeen = 3))
        repo.saveEntry(id, UnitEntry("res-2", "1002", "2", result = UnitResult.SUBLEASED, occupantName = "Al Noor Auto", photos = listOf(evidence)))

        ok("""{"visitId":"x","state":"DONE","units":2,"flaggedUnits":1,"photosExpected":1}""", 201)
        ok("""{"imageId":"img-u2.jpg","visitId":"x","resultId":"res-2","role":"EVIDENCE","sizeBytes":10,"sha256":"A"}""", 201)
        val result = repo.finish(id)
        assertEquals(FinishOutcome.SENT, result.outcome)

        val visit = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(2, visit["units"]!!.jsonArray.size)
        assertEquals("SUBLEASED", visit["units"]!!.jsonArray[1].jsonObject["result"]!!.jsonPrimitive.content)
        val upload = server.takeRequest()
        assertTrue(upload.path!!.contains("role=EVIDENCE") && upload.path!!.contains("result=res-2"))
        assertFalse(File(evidence.path).exists()) // deleted once the server has it
        assertEquals("DONE", repo.plans.value.single().state)
        assertNull(repo.draft(id))
        assertFalse(repo.hasWaiting())
    }

    @Test fun FR036_without_signal_the_visit_waits_encrypted_and_survives_a_restart() = runBlocking {
        val repo = app()
        val id = startVisit(repo)
        val evidence = photo("u1.jpg")
        repo.saveEntry(id, UnitEntry("res-1", "1001", "1", result = UnitResult.REJECTED, reasons = listOf("UNSAFE_USE"), photos = listOf(evidence)))
        repo.saveEntry(id, UnitEntry("res-2", "1002", "2", result = UnitResult.PENDING, note = "Locked"))

        offline = true
        assertEquals(FinishOutcome.QUEUED, repo.finish(id).outcome)
        assertTrue(repo.hasWaiting())
        assertEquals("COME_BACK", repo.plans.value.single().state) // worked out on the phone
        // Nothing readable on disk: the store and the photo are both sealed.
        assertFalse(storeFile.readText(Charsets.ISO_8859_1).contains("UNSAFE_USE"))
        assertTrue(sealer.isSealed(File(evidence.path).readBytes()))

        // Closed and opened again, still without signal: the visit and the plan are still there.
        val again = app()
        assertTrue(again.hasWaiting())
        assertEquals(1, again.waitingVisits.value)
        assertEquals("597-559", again.plans.value.single().propertyCode)

        offline = false
        server.takeRequest(); server.takeRequest()
        ok("""{"visitId":"x","state":"COME_BACK","units":2,"flaggedUnits":1,"photosExpected":1}""", 201)
        ok("""{"imageId":"img-u1.jpg","visitId":"x","resultId":"res-1","role":"EVIDENCE","sizeBytes":10,"sha256":"A"}""", 201)
        assertEquals(2, again.sendQueued())
        server.takeRequest()
        val upload = server.takeRequest()
        // The photo goes up as it was taken, not as stored on the phone.
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "u1.jpg".toByteArray(), upload.body.readByteArray())
        assertFalse(again.hasWaiting())
    }

    @Test fun FR031_2_away_from_the_property_no_location_is_sent() = runBlocking {
        val repo = app()
        val id = startVisit(repo)
        server.takeRequest(); server.takeRequest()
        // A location taken earlier is dropped once the inspector says they are not at the property.
        repo.update(id) { it.copy(atProperty = false, latitude = 25.01, longitude = 55.15) }
        repo.saveEntry(id, UnitEntry("res-1", "1001", "1", result = UnitResult.AS_RECORDED))
        ok("""{"visitId":"x","state":"COME_BACK","units":1,"flaggedUnits":0,"photosExpected":0}""", 201)
        repo.finish(id)
        val visit = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("false", visit["atProperty"]!!.jsonPrimitive.content)
        assertNull(visit["latitude"])
    }

    @Test fun FR031_2_at_the_property_the_distance_from_the_office_is_worked_out_on_the_phone() = runBlocking {
        val repo = app()
        ok(plan.replace("\"serverTimeUtc\"", "\"officeLatitude\":25.0,\"officeLongitude\":55.0,\"serverTimeUtc\""))
        repo.refreshPlan()
        val p = repo.plans.value.single()
        repo.startVisit(p)
        repo.update(p.id) { it.copy(atProperty = true, latitude = 26.0, longitude = 55.0) }
        assertEquals(111.2, repo.officeDistanceKm(repo.draft(p.id)!!)!!, 0.05)
    }

    @Test fun a_retry_sends_the_same_visit_id_so_it_is_not_stored_twice() = runBlocking {
        val repo = app()
        val id = startVisit(repo)
        server.takeRequest(); server.takeRequest()
        repo.saveEntry(id, UnitEntry("res-1", "1001", "1", result = UnitResult.AS_RECORDED))
        ok("""{"title":"Busy","status":503}""", 503)
        assertEquals(FinishOutcome.QUEUED, repo.finish(id).outcome)
        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["visitId"]!!.jsonPrimitive.content
        ok("""{"visitId":"$first","state":"COME_BACK","units":1,"flaggedUnits":0,"photosExpected":0}""")
        assertEquals(1, repo.sendQueued())
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["visitId"]!!.jsonPrimitive.content
        assertEquals(first, second)
    }

    @Test fun a_refused_visit_stays_open_on_the_phone_with_the_reason() = runBlocking {
        val repo = app()
        val id = startVisit(repo)
        server.takeRequest(); server.takeRequest()
        repo.saveEntry(id, UnitEntry("res-1", "1001", "1", result = UnitResult.AS_RECORDED))
        ok("""{"title":"Unit 1001 is not a unit of this property and tenant. Refresh the plan.","status":404,"code":"UNIT_NOT_FOUND"}""", 404)
        val result = repo.finish(id)
        assertEquals(FinishOutcome.REJECTED, result.outcome)
        assertTrue(result.message!!.contains("Refresh the plan"))
        assertNotNull(repo.draft(id)) // nothing lost
        assertFalse(repo.hasWaiting())
    }

    @Test fun an_incomplete_unit_stops_the_send() = runBlocking {
        val repo = app()
        val id = startVisit(repo)
        repo.saveEntry(id, UnitEntry("res-1", "1002", "2", result = UnitResult.SUBLEASED))
        assertEquals(InspectionRules.Problem.OCCUPANT, repo.finishProblem(repo.draft(id)!!)!!.second)
        assertEquals(FinishOutcome.REJECTED, repo.finish(id).outcome)
    }
}
