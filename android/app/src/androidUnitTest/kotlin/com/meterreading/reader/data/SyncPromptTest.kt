package com.meterreading.reader.data

import com.meterreading.reader.platform.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.datetime.Instant

/** FR-020.4: ask "Send now / Later" when signal is back; never interrupt a capture; Later waits. */
class SyncPromptTest {
    private val now = Instant.parse("2026-10-04T08:00:00Z")

    @Test
    fun `asks when readings wait and signal is back`() {
        assertTrue(SyncPrompt.shouldAsk(waiting = true, connected = true, snoozedUntil = null, now = now))
    }

    @Test
    fun `does not ask without signal or with nothing waiting`() {
        assertFalse(SyncPrompt.shouldAsk(waiting = true, connected = false, snoozedUntil = null, now = now))
        assertFalse(SyncPrompt.shouldAsk(waiting = false, connected = true, snoozedUntil = null, now = now))
    }

    @Test
    fun `never interrupts a capture`() {
        assertFalse(SyncPrompt.shouldAsk(waiting = true, connected = true, snoozedUntil = null, now = now, capturing = true))
    }

    @Test
    fun `Later waits thirty minutes, then asks again`() {
        val until = now.plus(SyncPrompt.SNOOZE)
        assertFalse(SyncPrompt.shouldAsk(true, true, until, now.plus(SyncPrompt.SNOOZE).minusSeconds(1)))
        assertTrue(SyncPrompt.shouldAsk(true, true, until, until))
    }
}
