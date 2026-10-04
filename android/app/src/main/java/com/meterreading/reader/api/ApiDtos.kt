package com.meterreading.reader.api

import kotlinx.serialization.Serializable

// JSON shapes of the Meter Reading API (api/src/MeterReading.Api/Contracts/Dtos.cs).
// Readings arrive as decimals; the app works in whole units for now.

@Serializable
data class PeriodDto(val code: String, val startDate: String, val endDate: String, val status: String)

@Serializable
data class MeDto(val readerId: String, val displayName: String, val teamCode: String? = null, val openPeriod: PeriodDto? = null)

@Serializable
data class ZoneDto(val code: String, val name: String? = null)

@Serializable
data class PropertyDto(
    val code: String,
    val name: String? = null,
    val zoneCode: String,
    val routeSequence: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
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

@Serializable
data class ProblemDto(val title: String? = null, val status: Int? = null, val code: String? = null)
