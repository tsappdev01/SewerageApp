package com.meterreading.reader.data

import java.time.LocalDate
import java.time.LocalDateTime

enum class MeterType { IRRIGATION, SEWERAGE }

/** Meter status LOV codes (spec §6.1). */
enum class MeterCondition { WORKING, DAMAGED, SUBMERSED, NOT_ACCESSIBLE, METER_REPLACED, REMOVED }

/** Image roles (spec FR-008.7). */
enum class ImageRole { DISPLAY, CONTEXT, OBSTRUCTION, DAMAGE, OLD_METER_FINAL, NEW_METER }

enum class ReasonCategory { DAMAGE, ACCESS, REMOVAL, REPLACEMENT }

/** Assignment status as the reader sees it (spec §7.1 C). */
enum class ReadingState {
    PENDING, QUEUED, SENT, CHECKING, READ_AGAIN, REVISIT;

    val canCapture: Boolean get() = this == PENDING || this == READ_AGAIN || this == REVISIT
}

data class Property(val code: String, val name: String, val zoneCode: String, val route: Int)

data class Meter(
    val id: Long,
    val number: String,
    val type: MeterType,
    val propertyCode: String,
    val zoneCode: String,
    val route: Int,
    val registerDigits: Int,
    /** Last approved actual reading; null for a new meter's first reading (BR-004). */
    val previousReading: Long?,
    val previousDate: LocalDate?,
    /** Upper end of the expected consumption range, sent by the server (BR-008). */
    val expectedHigh: Long,
    val state: ReadingState = ReadingState.PENDING,
    val supervisorNote: String? = null,
)

data class Reading(
    val transactionId: String,
    val meterId: Long,
    val condition: MeterCondition,
    val value: Long?,
    val capturedAt: LocalDateTime,
    val state: ReadingState,
    val needsCheck: Boolean,
)

data class ReadingDraft(
    val transactionId: String,
    val meterId: Long,
    val condition: MeterCondition,
    val reasonCode: String?,
    val note: String,
    val numbers: Map<NumberTarget, Long>,
    val newMeterNumber: String?,
    val photoPaths: Map<ImageRole, String>,
    val readerConfirmedWarning: Boolean,
    val capturedAt: LocalDateTime,
) {
    val value: Long? get() = numbers[NumberTarget.CURRENT] ?: numbers[NumberTarget.OLD_FINAL]
}

enum class SubmitOutcome { SENT, QUEUED, CHECKING }

data class ZoneProgress(val code: String, val total: Int, val done: Int)

data class PropertyProgress(val property: Property, val meters: List<Meter>) {
    val done: Int get() = meters.count { !it.state.canCapture }
}
