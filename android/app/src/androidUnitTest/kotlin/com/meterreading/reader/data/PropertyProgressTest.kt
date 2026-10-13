package com.meterreading.reader.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Home counts properties, not meters: a property is done when all its meters are. */
class PropertyProgressTest {
    private fun meter(id: String, property: String, state: ReadingState = ReadingState.PENDING) =
        Meter(id, "$property-$id", MeterType.IRRIGATION, property, "597", 1, 5, 100, null, 900, state)

    @Test fun `a property is done only when every meter on it is done`() {
        val progress = PropertyProgress.of(listOf(
            meter("1", "A", ReadingState.SENT), meter("2", "A", ReadingState.CHECKING),   // done
            meter("3", "B", ReadingState.SENT), meter("4", "B"),                          // half read: still to visit
            meter("5", "C", ReadingState.READ_AGAIN),                                     // to visit again
            meter("6", "D", ReadingState.QUEUED),                                         // read, waiting to send: done
        ))
        assertEquals(PropertyProgress(done = 2, total = 4), progress)
        assertEquals(2, progress.left)
    }

    @Test fun `no meters means nothing to do`() {
        assertEquals(PropertyProgress(0, 0), PropertyProgress.of(emptyList()))
    }
}
