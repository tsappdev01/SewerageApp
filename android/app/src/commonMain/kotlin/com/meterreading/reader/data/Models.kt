package com.meterreading.reader.data

import com.meterreading.reader.platform.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

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

/** A current tenant of a property (vw_MR_Tenant). */
data class Tenant(val code: String, val companyName: String?) {
    val displayName: String get() = companyName?.takeIf { it.isNotBlank() } ?: code
}

data class Property(
    val code: String,
    val name: String,
    val zoneCode: String,
    val route: Int,
    val tenantCode: String? = null,
    /** The tenant's company, from vw_MR_Property. */
    val companyName: String? = null,
    /** Current tenants; the reader checks one before saving a reading (FR-006.12). Empty: none on record. */
    val tenants: List<Tenant> = emptyList(),
) {
    /** What the reader sees under the code: the company when there is one, else the property name. */
    val displayName: String get() = companyName?.takeIf { it.isNotBlank() } ?: name
}

data class Meter(
    /** The meter's barcode in the source system. */
    val id: String,
    val number: String,
    val type: MeterType,
    val propertyCode: String,
    val zoneCode: String,
    val route: Int,
    val registerDigits: Int,
    /** Last billed reading; 0 or null for a meter never read. */
    val previousReading: Long?,
    val previousDate: LocalDate?,
    /** Upper end of the expected consumption range, sent by the server (BR-008). Null: no warning. */
    val expectedHigh: Long?,
    val state: ReadingState = ReadingState.PENDING,
    val supervisorNote: String? = null,
    /** Consumption of the last reading, "used last time". */
    val lastConsumption: Long? = null,
    /** True when the meter has never been read (BR-004). */
    val isFirstReading: Boolean = previousReading == null,
)

data class Reading(
    val transactionId: String,
    val meterId: String,
    val condition: MeterCondition,
    val value: Long?,
    val capturedAt: LocalDateTime,
    val state: ReadingState,
    val needsCheck: Boolean,
)

data class ReadingDraft(
    val transactionId: String,
    val meterId: String,
    val condition: MeterCondition,
    val reasonCode: String?,
    val note: String,
    val numbers: Map<NumberTarget, Long>,
    val newMeterNumber: String?,
    val photos: List<DraftPhoto>,
    val readerConfirmedWarning: Boolean,
    val capturedAt: LocalDateTime,
    /** Sub-tenant name typed by the reader, if the premises has one. */
    val subTenant: String? = null,
    /** The tenant the reader checked on site (FR-006.12). */
    val tenantCode: String? = null,
) {
    val value: Long? get() = numbers[NumberTarget.CURRENT] ?: numbers[NumberTarget.OLD_FINAL]
}

/** A photo taken for a reading. [imageId] is made once, so a retried upload is recognised (FR-009). */
data class DraftPhoto(val imageId: String, val role: ImageRole, val path: String)

enum class SubmitOutcome { SENT, QUEUED, CHECKING, REJECTED }

/** What happened to a reading; [message] explains a rejection in the server's words. */
data class SubmitResult(val outcome: SubmitOutcome, val message: String? = null)

sealed interface SignInResult {
    data object Success : SignInResult
    data class Failed(val message: String) : SignInResult
}

data class ZoneProgress(val code: String, val total: Int, val done: Int)

data class PropertyProgress(val property: Property, val meters: List<Meter>) {
    val done: Int get() = meters.count { !it.state.canCapture }
}
