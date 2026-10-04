package com.meterreading.reader.data

import com.meterreading.reader.data.ReadingRules.Check
import org.junit.Assert.assertEquals
import org.junit.Test

/** Vectors from spec Appendix B (5-digit register). */
class ReadingRulesTest {
    private fun check(previous: Long?, current: Long, expectedHigh: Long = 900) =
        ReadingRules.check(previous, current, registerDigits = 5, expectedHigh = expectedHigh)

    @Test fun `B1 working meter consumption`() = assertEquals(Check.Ok(2_500), check(50_000, 52_500, expectedHigh = 3_000))

    @Test fun `B3 above expected range is high`() = assertEquals(Check.High(1_000), check(50_000, 51_000))

    @Test fun `B4 lower reading far from max is lower`() = assertEquals(Check.Lower, check(50_000, 49_900))

    @Test fun `B5 rollover near max`() = assertEquals(Check.Rollover(170), check(99_950, 120))

    @Test fun `B6 rollover out of range is high`() = assertEquals(Check.High(9_050), check(99_950, 9_000))

    @Test fun `B7 zero consumption is ok`() = assertEquals(Check.Ok(0), check(50_000, 50_000))

    @Test fun `B10 first reading counts from zero`() = assertEquals(Check.Ok(35), check(null, 35))

    @Test fun `register max`() = assertEquals(99_999L, ReadingRules.registerMax(5))

    @Test fun `no expected range means no high warning`() = assertEquals(Check.Ok(9_000), ReadingRules.check(50_000, 59_000, 5, null))
}

class SearchTest {
    private val meters = listOf(
        Meter("BC0001", "2001-I", MeterType.IRRIGATION, "1499-W1", "598", 1, 5, 100, null, 900),
        Meter("BC0002", "2002-2", MeterType.SEWERAGE, "1499-W1", "598", 2, 5, 100, null, 900, ReadingState.SENT),
        Meter("BC0003", "1497-S", MeterType.SEWERAGE, "1497", "598", 1, 5, 100, null, 900),
        Meter("BC0004", "1001-I", MeterType.IRRIGATION, "1100", "597", 1, 5, 100, null, 900),
    )
    private val properties = listOf(
        PropertyProgress(Property("1499-W1", "Building 1499-W1", "598", 1), meters.filter { it.propertyCode == "1499-W1" }),
        PropertyProgress(Property("1497", "Villa 1497", "598", 2), meters.filter { it.propertyCode == "1497" }),
        PropertyProgress(Property("1100", "Villa 1100", "597", 1), meters.filter { it.propertyCode == "1100" }),
    )

    @Test fun `dashes and case are ignored`() = assertEquals(true, Search.matches("1499-W1", "1499w1"))

    @Test fun `partial digits find both buildings`() =
        assertEquals(listOf("1499-W1", "1497"), Search.run(SearchQuery("149"), properties).properties.map { it.property.code })

    @Test fun `meter number finds meter in other building`() =
        assertEquals(listOf("BC0004"), Search.run(SearchQuery("1001"), properties).meters.map { it.id })

    @Test fun `to read filter drops read meters`() =
        assertEquals(listOf("BC0001"), Search.run(SearchQuery("1499", DoneFilter.TO_READ), properties).properties.single().meters.map { it.id })

    @Test fun `zone scope`() =
        assertEquals(emptyList<String>(), Search.run(SearchQuery("1100", zoneCode = "598"), properties).properties.map { it.property.code })

    @Test fun `highlight skips dash`() = assertEquals(0..5, Search.highlightRange("1499-W1", "1499W"))
}

class ReconciliationTest {
    @Test fun `buckets add up and uploaded excludes waiting`() {
        fun m(id: Int, zone: String, state: ReadingState) =
            Meter("M$id", "M$id", MeterType.SEWERAGE, "P", zone, 1, 5, 0, null, 900, state)
        val r = Reconciliation.from(
            listOf(
                m(1, "597", ReadingState.SENT), m(2, "597", ReadingState.QUEUED), m(3, "598", ReadingState.PENDING),
                m(4, "598", ReadingState.CHECKING), m(5, "598", ReadingState.READ_AGAIN),
            ),
            emptyList(),
        )
        assertEquals(5, r.meters)
        assertEquals(4, r.read)
        assertEquals(3, r.uploaded)
        assertEquals(listOf(ZoneReconciliation("597", 2, 2, 1), ZoneReconciliation("598", 3, 2, 2)), r.zones)
    }
}
