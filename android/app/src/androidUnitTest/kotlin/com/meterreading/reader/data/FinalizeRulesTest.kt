package com.meterreading.reader.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** FR-037.2: a property can be finalized once every meter's reading is on the server. */
class FinalizeRulesTest {
    private fun meter(id: String, state: ReadingState) =
        Meter(id, id, MeterType.SEWERAGE, "598-1000", "598", 1, 5, 100, null, 900, state)

    @Test fun `FR037_2 sent and visit-again meters count as read`() =
        assertEquals(0, FinalizeRules.left(listOf(meter("a", ReadingState.SENT), meter("b", ReadingState.REVISIT))))

    @Test fun `FR037_2 unread, waiting, checking and read-again meters are left`() =
        assertEquals(
            4,
            FinalizeRules.left(
                listOf(
                    meter("a", ReadingState.PENDING), meter("b", ReadingState.QUEUED),
                    meter("c", ReadingState.CHECKING), meter("d", ReadingState.READ_AGAIN), meter("e", ReadingState.SENT),
                ),
            ),
        )
}
