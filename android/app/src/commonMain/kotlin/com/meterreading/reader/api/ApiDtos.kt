package com.meterreading.reader.api

import com.meterreading.reader.platform.*
import kotlinx.serialization.Serializable

// JSON shapes of the Meter Reading API (api/src/MeterReading.Api/Contracts/Dtos.cs).
// Readings arrive as decimals; the app works in whole units for now.

@Serializable
data class PeriodDto(val code: String, val startDate: String, val endDate: String, val status: String)

@Serializable
data class MeDto(
    val readerId: String,
    val displayName: String,
    val teamCode: String? = null,
    val openPeriod: PeriodDto? = null,
    /** Field Inspection is set up on the server (spec §21). */
    val canInspect: Boolean = false,
)

@Serializable
data class ZoneDto(val code: String, val name: String? = null)

@Serializable
data class TenantDto(val code: String, val companyName: String? = null)

@Serializable
data class PropertyDto(
    val code: String,
    val name: String? = null,
    val zoneCode: String,
    val routeSequence: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val tenantCode: String? = null,
    val companyName: String? = null,
    val tenants: List<TenantDto> = emptyList(),
)

@Serializable
data class MeterDto(
    val id: String,
    val number: String,
    val type: String,
    val propertyCode: String,
    val zoneCode: String,
    val routeSequence: Int? = null,
    val registerDigits: Int,
    val decimalDigits: Int = 0,
    val previousReading: Double? = null,
    val lastConsumption: Double? = null,
    val isFirstReading: Boolean = false,
    val averageConsumption: Double? = null,
    val expectedHigh: Double? = null,
    val state: String,
    val supervisorNote: String? = null,
    val lastTransactionId: String? = null,
)

@Serializable
data class SyncDto(
    val period: PeriodDto,
    val zones: List<ZoneDto>,
    val properties: List<PropertyDto>,
    val meters: List<MeterDto>,
    val serverTimeUtc: String,
    /** FR-006.2: PMS's meter statuses (vw_MR_MeterStatus), in its order. */
    val statuses: List<MeterStatusDto> = emptyList(),
)

@Serializable
data class MeterStatusDto(val code: String, val name: String, val kind: String, val sequence: Int = 0)

@Serializable
data class ReadingDto(
    val transactionId: String,
    val meterId: String,
    val meterNumber: String? = null,
    val meterType: String? = null,
    val condition: String,
    val reasonCode: String? = null,
    val newReading: Double? = null,
    val consumption: Double? = null,
    val capturedAtUtc: String,
    val receivedAtUtc: String,
    val status: String,
    val state: String,
    val note: String? = null,
    val photosExpected: Int = 0,
    val photosReceived: Int = 0,
)

@Serializable
data class SubmitReadingRequest(
    val transactionId: String,
    val meterId: String,
    val condition: String,
    val reasonCode: String? = null,
    val note: String? = null,
    val newReading: Long? = null,
    val oldFinalReading: Long? = null,
    val newMeterNumber: String? = null,
    val newOpeningReading: Long? = null,
    val newCurrentReading: Long? = null,
    val readerConfirmedWarning: Boolean = false,
    val capturedAtUtc: String,
    val photoCount: Int = 0,
    val subTenant: String? = null,
    val tenantCode: String? = null,
    /** PMS's status code (vw_MR_MeterStatus); the server stores it and takes the kind from it. */
    val statusCode: String? = null,
)

@Serializable
data class SubmitReadingResponse(
    val transactionId: String,
    val meterId: String,
    val status: String,
    val state: String,
    val consumption: Double? = null,
    val exceptions: List<String> = emptyList(),
)

/** This phone's registration (spec FR-002): its id and secret key, and IT's name for it. */
data class DeviceCredentials(val deviceId: String, val deviceKey: String, val label: String, val checkCode: String = "")

@Serializable
data class RegisterDeviceRequest(val code: String, val model: String? = null, val androidVersion: String? = null, val appVersion: String? = null)

@Serializable
data class RegisterDeviceResponse(val deviceId: String, val deviceKey: String, val label: String = "")

/** FR-002.8: the phone asks IT to register it by its ANDROID_ID. */
@Serializable
data class EnrolDeviceRequest(val androidId: String, val model: String? = null, val androidVersion: String? = null, val appVersion: String? = null)

@Serializable
data class EnrolDeviceResponse(val deviceId: String, val deviceKey: String, val checkCode: String, val status: String = "PENDING")

/** PENDING, REGISTERED or REVOKED, and the reader IT gave the phone. */
@Serializable
data class DeviceStatusDto(
    val status: String,
    val androidId: String? = null,
    val checkCode: String? = null,
    val readerLogin: String? = null,
    val label: String? = null,
)

@Serializable
data class ProblemDto(val title: String? = null, val status: Int? = null, val code: String? = null)

@Serializable
data class ImageUploadResponse(val imageId: String, val transactionId: String, val role: String, val sizeBytes: Int, val sha256: String)

/** A row of mr.MaintainFinalizedMeter (FR-037.3): an empty side is meter number "0" and 0s. */
@Serializable
data class FinalizedMeterDto(
    val sewerageMeterNumber: String,
    val seweragePrevious: Double,
    val sewerageCurrent: Double,
    val sewerageConsumption: Double,
    val irrigationMeterNumber: String,
    val irrigationPrevious: Double,
    val irrigationCurrent: Double,
    val irrigationConsumption: Double,
    val sewerageAmount: Double = 0.0,
    val irrigationAmount: Double = 0.0,
    val sewerageVat: Double = 0.0,
    val irrigationVat: Double = 0.0,
    val totalAmount: Double = 0.0,
    val irrigationNoCharge: Boolean = false,
    val tenPercentAmount: Double = 0.0,
    val irrigationAmountCalculated: Double = 0.0,
)

/** What "Finalize property" wrote (FR-037). */
@Serializable
data class FinalizedPropertyDto(
    val periodCode: String,
    val propertyCode: String,
    val propertyId: Long? = null,
    val finalizedAtUtc: String,
    val rows: List<FinalizedMeterDto>,
)
