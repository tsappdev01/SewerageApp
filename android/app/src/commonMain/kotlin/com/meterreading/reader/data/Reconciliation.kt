package com.meterreading.reader.data

import com.meterreading.reader.platform.*
import kotlinx.datetime.LocalDateTime

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
            val zones = meters.groupBy { it.zoneCode }.entries.sortedBy { it.key }.map { (code, list) ->
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

/**
 * Home's progress in properties, as readers work: a property is done when every one of its meters is
 * done (read, or sent for checking), and still to visit while any meter on it can be read.
 */
data class HomeProgress(val done: Int, val total: Int) {
    val left: Int get() = total - done

    companion object {
        fun of(meters: List<Meter>): HomeProgress {
            val byProperty = meters.groupBy { it.propertyCode }
            return HomeProgress(byProperty.values.count { group -> group.none { it.state.canCapture } }, byProperty.size)
        }
    }
}
