package com.meterreading.reader.api

import kotlinx.serialization.Serializable

// Field Inspection JSON (api/src/MeterReading.Api/Contracts/InspectionDtos.cs, spec §21).

@Serializable
data class InspectionPlanDto(
    val periodCode: String,
    val planDate: String,
    val propertyCode: String,
    val tenantCode: String,
    val companyName: String? = null,
    val activeUnits: Int = 0,
    val inactiveUnits: Int = 0,
    val totalUnits: Int = 0,
    /** NOT_STARTED, COME_BACK or DONE. */
    val state: String = "NOT_STARTED",
    val checkedUnits: Int = 0,
    val flaggedUnits: Int = 0,
    val lastVisitAtUtc: String? = null,
) {
    /** One plan row: a property and tenant in a period. Used as the key everywhere on the phone. */
    val id: String get() = "$periodCode|$propertyCode|$tenantCode"
}

@Serializable
data class InspectionPlanListDto(
    val today: String,
    val from: String,
    val to: String,
    val plans: List<InspectionPlanDto>,
    val serverTimeUtc: String,
)

@Serializable
data class LastUnitResultDto(val result: String, val atUtc: String, val occupantName: String? = null, val peopleSeen: Int? = null)

@Serializable
data class InspectionUnitDto(
    val unitId: String,
    val buildingName: String? = null,
    val unitCode: String,
    val category: String? = null,
    val categoryName: String? = null,
    val subTenantName: String? = null,
    val active: Boolean = true,
    val last: LastUnitResultDto? = null,
)

@Serializable
data class InspectionUnitsDto(val plan: InspectionPlanDto, val units: List<InspectionUnitDto>)

@Serializable
data class UnitResultRequest(
    val resultId: String,
    val unitId: String? = null,
    val result: String,
    val peopleSeen: Int? = null,
    val occupantName: String? = null,
    val reasons: List<String>? = null,
    val note: String? = null,
    val photoCount: Int = 0,
    val unitCode: String? = null,
    val buildingName: String? = null,
    val category: String? = null,
)

@Serializable
data class SubmitInspectionRequest(
    val visitId: String,
    val periodCode: String,
    val propertyCode: String,
    val tenantCode: String,
    val startedAtUtc: String,
    val finishedAtUtc: String,
    val units: List<UnitResultRequest>,
    val personMet: String? = null,
    val hasSignature: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val gpsAccuracyM: Double? = null,
)

@Serializable
data class SubmitInspectionResponse(val visitId: String, val state: String, val units: Int, val flaggedUnits: Int, val photosExpected: Int)

@Serializable
data class InspectionImageResponse(val imageId: String, val visitId: String, val resultId: String? = null, val role: String, val sizeBytes: Int, val sha256: String)
