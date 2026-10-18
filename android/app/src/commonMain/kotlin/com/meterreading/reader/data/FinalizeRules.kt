package com.meterreading.reader.data

/**
 * FR-037: "Finalize property". Mirrors the server's FinalizeService; the server decides.
 * A meter counts as read once its reading is on the server: sent, or "visit again" (not accessible).
 * A reading still waiting on the phone does not count yet.
 */
object FinalizeRules {
    fun isRead(state: ReadingState): Boolean = state == ReadingState.SENT || state == ReadingState.REVISIT

    /** Meters of the property still to read or to send before it can be finalized. */
    fun left(meters: List<Meter>): Int = meters.count { !isRead(it.state) }
}

sealed interface FinalizeResult {
    /** Written: [rows] rows of mr.MaintainFinalizedMeter. */
    data class Done(val rows: Int) : FinalizeResult
    data object NoSignal : FinalizeResult
    /** The server said no, in its own words (e.g. "1 meter of this property is still to read."). */
    data class Refused(val message: String) : FinalizeResult
}
