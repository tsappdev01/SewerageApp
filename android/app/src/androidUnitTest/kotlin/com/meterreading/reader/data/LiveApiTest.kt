package com.meterreading.reader.data

import com.meterreading.reader.platform.*
import com.meterreading.reader.api.ApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.datetime.LocalDateTime

/**
 * Runs only when MR_API_URL points at an API in Development with the dev data (api/README.md).
 * Reads only, so it leaves the database as it found it.
 */
class LiveApiTest {
    private val url: String? = System.getenv("MR_API_URL")?.takeIf { it.isNotBlank() }

    @Test
    fun `signs in and syncs the development data`() {
        assumeTrue("MR_API_URL not set", url != null)
        val repo = ApiMeterRepository(ApiClient(url!!))
        assertEquals(SignInResult.Success, runBlocking { repo.signIn("rashid@dip.example") })
        assertEquals("Rashid", repo.readerName.value)
        assertEquals(17, repo.meters.value.size) // plot 4001 has no tenant, so the property view leaves it out
        assertEquals("Sandline Logistics LLC", repo.property("1499-W1").displayName)
        assertEquals("BC0003", repo.nextMeter(repo.meters.value)?.id) // first unread meter on the route
        assertEquals(ReadingState.READ_AGAIN, repo.meter("BC0008").state)
    }

    @Test
    fun `a bad reading is refused with the server's reason`() {
        assumeTrue("MR_API_URL not set", url != null)
        val repo = ApiMeterRepository(ApiClient(url!!))
        runBlocking { repo.signIn("rashid@dip.example") }
        val tooLong = ReadingDraft(
            transactionId = randomUuid(), meterId = "BC0003", condition = MeterCondition.WORKING,
            reasonCode = null, note = "", numbers = mapOf(NumberTarget.CURRENT to 123_456L), newMeterNumber = null,
            photos = emptyList(), readerConfirmedWarning = false, capturedAt = LocalDateTime.now().minusMinutes(1),
            tenantCode = "T-0102",
        )
        val result = runBlocking { repo.submit(tooLong) }
        assertEquals(SubmitOutcome.REJECTED, result.outcome)
        assertEquals("The meter has 5 digits.", result.message)
    }

    @Test
    fun `another reader sees a meter read by someone else as done`() {
        assumeTrue("MR_API_URL not set", url != null)
        val repo = ApiMeterRepository(ApiClient(url!!))
        runBlocking { repo.signIn("rashid@dip.example") }
        assertEquals(ReadingState.SENT, repo.meter("BC0016").state) // read by Anil in the dev data
        assertEquals(ReadingState.REVISIT, repo.meter("BC0017").state)
    }

    @Test
    fun `FR006_12 tenants come from vw_MR_Tenant and a property without one cannot be read`() {
        assumeTrue("MR_API_URL not set", url != null)
        val repo = ApiMeterRepository(ApiClient(url!!))
        runBlocking { repo.signIn("rashid@dip.example") }
        assertEquals(listOf("T-0102", "T-0199"), repo.property("1101").tenants.map { it.code }.sorted())
        assertTrue(repo.property("3010").tenants.isEmpty()) // lease ended
        val draft = ReadingDraft(
            transactionId = randomUuid(), meterId = "BC0017", condition = MeterCondition.WORKING,
            reasonCode = null, note = "", numbers = mapOf(NumberTarget.CURRENT to 4_800L), newMeterNumber = null,
            photos = emptyList(), readerConfirmedWarning = false, capturedAt = LocalDateTime.now().minusMinutes(1),
            tenantCode = "T-0301",
        )
        assertEquals(SubmitResult(SubmitOutcome.REJECTED, TenantRules.Problem.NO_TENANT.message), runBlocking { repo.submit(draft) })
    }
}
