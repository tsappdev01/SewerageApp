package com.meterreading.reader.data

import java.time.Duration
import java.time.Instant

/**
 * FR-020.4 (changed 2026-10-04): readings saved without signal are not sent by themselves. When
 * signal is back, the reader is asked "Send now" or "Later"; "Later" waits [SNOOZE] before asking
 * again. A capture in progress is never interrupted.
 */
object SyncPrompt {
    val SNOOZE: Duration = Duration.ofMinutes(30)

    /** After a "Send now" that could not finish (signal dropped again), wait this long before asking again. */
    val RETRY: Duration = Duration.ofMinutes(5)

    fun shouldAsk(waiting: Boolean, connected: Boolean, snoozedUntil: Instant?, now: Instant, capturing: Boolean = false): Boolean =
        waiting && connected && !capturing && (snoozedUntil == null || !now.isBefore(snoozedUntil))
}
