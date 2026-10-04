package com.meterreading.reader.data

import java.time.LocalDateTime

data class ZoneReconciliation(val zoneCode: String, val meters: Int, val read: Int, val uploaded: Int) {
    val waiting: Int get() = read - uploaded
}

/**
 * What the phone holds against what the server has, for the open period.
 * Every meter is in exactly one bucket, so the buckets add up to [meters].
 */
data class Reconciliation(
    val meters: Int,
    val accepted: Int,
    val checking: Int,
    val readAgain: Int,
    val revisit: Int,
    val waiting: Int,
    val notRead: Int,
    val zones: List<ZoneReconciliation>,
    val lastUpload: LocalDateTime?,
) {
    val read: Int get() = meters - notRead
    val uploaded: Int get() = read - waiting
    val allUploaded: Boolean get() = waiting == 0

    companion object {
        fun from(meters: List<Meter>, readings: List<Reading>): Reconciliation {
            fun count(state: ReadingState) = meters.count { it.state == state }
            val zones = meters.groupBy { it.zoneCode }.toSortedMap().map { (code, list) ->
                val notRead = list.count { it.state == ReadingState.PENDING }
                val waiting = list.count { it.state == ReadingState.QUEUED }
                ZoneReconciliation(code, list.size, list.size - notRead, list.size - notRead - waiting)
            }
            return Reconciliation(
                meters = meters.size,
                accepted = count(ReadingState.SENT),
                checking = count(ReadingState.CHECKING),
                readAgain = count(ReadingState.READ_AGAIN),
                revisit = count(ReadingState.REVISIT),
                waiting = count(ReadingState.QUEUED),
                notRead = count(ReadingState.PENDING),
                zones = zones,
                lastUpload = readings.filter { it.state != ReadingState.QUEUED }.maxOfOrNull { it.capturedAt },
            )
        }
    }
}
