package com.meterreading.reader.api

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
)

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
data class DeviceCredentials(val deviceId: String, val deviceKey: String, val label: String)

@Serializable
data class RegisterDeviceRequest(val code: String, val model: String? = null, val androidVersion: String? = null, val appVersion: String? = null)

@Serializable
data class RegisterDeviceResponse(val deviceId: String, val deviceKey: String, val label: String = "")

@Serializable
data class ProblemDto(val title: String? = null, val status: Int? = null, val code: String? = null)

@Serializable
data class ImageUploadResponse(val imageId: String, val transactionId: String, val role: String, val sizeBytes: Int, val sha256: String)
