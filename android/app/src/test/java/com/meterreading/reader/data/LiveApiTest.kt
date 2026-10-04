package com.meterreading.reader.data

import com.meterreading.reader.api.ApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.UUID

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
        assertEquals(18, repo.meters.value.size)
        assertEquals("BC0003", repo.nextMeter(repo.meters.value)?.id) // first unread meter on the route
        assertEquals(ReadingState.READ_AGAIN, repo.meter("BC0008").state)
    }

    @Test
    fun `a bad reading is refused with the server's reason`() {
        assumeTrue("MR_API_URL not set", url != null)
        val repo = ApiMeterRepository(ApiClient(url!!))
        runBlocking { repo.signIn("rashid@dip.example") }
        val tooLong = ReadingDraft(
            transactionId = UUID.randomUUID().toString(), meterId = "BC0003", condition = MeterCondition.WORKING,
            reasonCode = null, note = "", numbers = mapOf(NumberTarget.CURRENT to 123_456L), newMeterNumber = null,
            photos = emptyList(), readerConfirmedWarning = false, capturedAt = LocalDateTime.now().minusMinutes(1),
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
}
