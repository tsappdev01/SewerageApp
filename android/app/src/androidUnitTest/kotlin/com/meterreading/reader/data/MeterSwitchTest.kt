package com.meterreading.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a property: switch between its Sewerage and Irrigation meters, one at a time; photos off. */
class MeterSwitchTest {
    private fun meter(id: String, type: MeterType, property: String = "597-646", state: ReadingState = ReadingState.PENDING, route: Int = 1) =
        Meter(id, "$property-$id", type, property, "597", route, 5, 100, null, 900, state)

    @Test fun `both meters of the property are offered, sewerage first`() {
        val irrigation = meter("I", MeterType.IRRIGATION)
        val sewerage = meter("S", MeterType.SEWERAGE)
        val elsewhere = meter("X", MeterType.SEWERAGE, property = "597-700")
        assertEquals(listOf("S", "I"), MeterSwitch.options(listOf(irrigation, elsewhere, sewerage), irrigation).map { it.id })
    }

    @Test fun `a meter already sent drops out, the open one stays`() {
        val sewerage = meter("S", MeterType.SEWERAGE, state = ReadingState.SENT)
        val irrigation = meter("I", MeterType.IRRIGATION)
        assertEquals(listOf("I"), MeterSwitch.options(listOf(sewerage, irrigation), irrigation).map { it.id })
        // Just sent and still on screen: it stays until the reader moves on.
        assertEquals(listOf("S", "I"), MeterSwitch.options(listOf(sewerage, irrigation), sewerage).map { it.id })
    }

    @Test fun `a property with one meter has nothing to switch to`() {
        val only = meter("S", MeterType.SEWERAGE)
        assertEquals(listOf(only), MeterSwitch.options(listOf(only), only))
    }

    @Test fun `photos are off - no photo step for any condition`() {
        assertFalse(StatusRules.PHOTOS_ON)
        MeterCondition.entries.forEach { c ->
            assertTrue("$c", StatusRules.stepsFor(c).none { it is Step.Photo })
            assertEquals(Step.Confirm, StatusRules.stepsFor(c).last())
        }
        assertEquals(
            listOf(Step.Number(NumberTarget.CURRENT, required = true), Step.Confirm),
            StatusRules.stepsFor(MeterCondition.WORKING),
        )
        // Switched back on, the photo steps return where they were.
        assertEquals(Step.Photo(ImageRole.DISPLAY), StatusRules.stepsFor(MeterCondition.WORKING, photos = true).first())
    }
}
