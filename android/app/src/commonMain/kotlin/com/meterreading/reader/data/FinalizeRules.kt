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

    /** FR-037.17: the month as DIP writes it, "2026-10" -> "10-2026"; anything else as it is. */
    fun monthLabel(periodCode: String): String {
        val m = Regex("""^(\d{4})-(\d{2})$""").matchEntire(periodCode) ?: return periodCode
        return "${m.groupValues[2]}-${m.groupValues[1]}"
    }
}

/** FR-037.13: what "Finalize month" did. */
sealed interface CloseMonthResult {
    /**
     * The month [periodCode] is closed ([alreadyClosed]: it was closed before), after [finalized] properties were
     * finalized with [invoices] invoices; [notComplete] were left with meters to read (FR-037.17).
     */
    data class Done(
        val periodCode: String,
        val alreadyClosed: Boolean,
        val finalized: Int = 0,
        val invoices: Int = 0,
        val notComplete: Int = 0,
    ) : CloseMonthResult
    /** [items] readings or photos could not be sent first: nothing was closed. */
    data class StillWaiting(val items: Int) : CloseMonthResult
    data object NoSignal : CloseMonthResult
    data class Refused(val message: String) : CloseMonthResult
}

sealed interface FinalizeResult {
    /** Written: [rows] rows of mr.MaintainFinalizedMeter. */
    data class Done(val rows: Int) : FinalizeResult
    data object NoSignal : FinalizeResult
    /** The server said no, in its own words (e.g. "1 meter of this property is still to read."). */
    data class Refused(val message: String) : FinalizeResult
}
