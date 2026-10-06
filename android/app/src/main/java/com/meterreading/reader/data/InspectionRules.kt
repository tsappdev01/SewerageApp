package com.meterreading.reader.data

import com.meterreading.reader.api.InspectionPlanDto
import com.meterreading.reader.api.InspectionUnitDto
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** What the inspector found in a unit (spec FR-032). Same codes as the server. */
enum class UnitResult { AS_RECORDED, VACANT, SUBLEASED, DISPUTED, REJECTED, PENDING }

/** Where a plan row stands (FR-034). */
enum class InspectionState { NOT_STARTED, COME_BACK, DONE }

/** The plan list's tabs. LATER is beyond this week and shows under Week. */
enum class PlanTab { TODAY, LATE, WEEK, DONE }

/** A photo taken during an inspection; [imageId] is made once so a retried upload is recognised. */
@Serializable
data class EvidencePhoto(val imageId: String, val path: String, val capturedAtUtc: String)

/**
 * What the inspector recorded for one unit. [unitId] is null for a unit found on site that is not on
 * the list (FR-035); then [unitCode] is what is written on the door.
 */
@Serializable
data class UnitEntry(
    val resultId: String,
    val unitId: String?,
    val unitCode: String,
    val buildingName: String? = null,
    val category: String? = null,
    val result: UnitResult? = null,
    val peopleSeen: Int? = null,
    val occupantName: String = "",
    val reasons: List<String> = emptyList(),
    val note: String = "",
    val photos: List<EvidencePhoto> = emptyList(),
) {
    val isNew: Boolean get() = unitId == null
    /** The key of the entry in its visit: the unit id, or the result id for a new unit. */
    val key: String get() = unitId ?: resultId
}

/** One visit to one plan row, from "Start inspection" until it is sent. Kept on the phone (encrypted) the whole time. */
@Serializable
data class VisitDraft(
    val visitId: String,
    val periodCode: String,
    val propertyCode: String,
    val tenantCode: String,
    val companyName: String? = null,
    val startedAtUtc: String,
    val finishedAtUtc: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val gpsAccuracyM: Double? = null,
    /** The answer to "Are you at the property now?" when the visit started; null until asked. */
    val atProperty: Boolean? = null,
    val personMet: String = "",
    val signature: EvidencePhoto? = null,
    /** By [UnitEntry.key], in the order recorded. */
    val entries: Map<String, UnitEntry> = emptyMap(),
) {
    val planId: String get() = "$periodCode|$propertyCode|$tenantCode"
    val recorded: List<UnitEntry> get() = entries.values.filter { it.result != null }
    val photoCount: Int get() = recorded.sumOf { it.photos.size } + (if (signature != null) 1 else 0)
}

/**
 * Field Inspection rules on the phone. They mirror the server's (api Domain/InspectionService.cs);
 * the server decides. Kept free of Android so they can be unit-tested.
 */
object InspectionRules {
    const val MAX_PHOTOS = 6
    const val MAX_PEOPLE = 999

    val flagged: Set<UnitResult> = setOf(UnitResult.SUBLEASED, UnitResult.DISPUTED, UnitResult.REJECTED)

    fun needsOccupant(r: UnitResult?) = r == UnitResult.SUBLEASED
    fun needsReason(r: UnitResult?) = r == UnitResult.DISPUTED || r == UnitResult.REJECTED || r == UnitResult.PENDING
    fun needsPhoto(r: UnitResult?) = r in flagged
    /** Details (who, why, photos) are asked only for these; the quick results need one tap. */
    fun hasDetails(r: UnitResult?) = r != null && r != UnitResult.AS_RECORDED

    /** Reason codes the inspector can tap for a result. Same codes go to the server. */
    fun reasonsFor(r: UnitResult?): List<String> = when (r) {
        UnitResult.SUBLEASED -> listOf("OTHER_COMPANY_SIGN", "STAFF_SAY_SO", "LICENCE_SHOWN", "DIFFERENT_TRADE", "OTHER")
        UnitResult.DISPUTED -> listOf("TENANT_DISAGREES", "RECORD_WRONG", "UNIT_SPLIT", "OTHER")
        UnitResult.REJECTED -> listOf("LABOUR_IN_WAREHOUSE", "UNSAFE_USE", "TRADE_NOT_ALLOWED", "OTHER")
        UnitResult.PENDING -> listOf("LOCKED", "NO_ACCESS", "REFUSED", "COME_BACK_LATER", "OTHER")
        UnitResult.VACANT -> listOf("EMPTY", "BEING_FITTED_OUT", "OTHER")
        else -> emptyList()
    }

    enum class Problem { NO_RESULT, UNIT_CODE, OCCUPANT, REASON, PHOTO, TOO_MANY_PHOTOS, PEOPLE }

    /** Why a unit cannot be saved yet, or null when it can (FR-032, FR-033, FR-035). */
    fun problem(e: UnitEntry): Problem? = when {
        e.result == null -> Problem.NO_RESULT
        e.isNew && e.unitCode.isBlank() -> Problem.UNIT_CODE
        needsOccupant(e.result) && e.occupantName.isBlank() -> Problem.OCCUPANT
        needsReason(e.result) && e.reasons.isEmpty() && e.note.isBlank() -> Problem.REASON
        needsPhoto(e.result) && e.photos.isEmpty() -> Problem.PHOTO
        e.photos.size > MAX_PHOTOS -> Problem.TOO_MANY_PHOTOS
        (e.peopleSeen ?: 0) !in 0..MAX_PEOPLE -> Problem.PEOPLE
        else -> null
    }

    /** Great-circle distance in km (haversine); mirrors the server's Domain/Geo.cs (FR-031.2). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        fun rad(d: Double) = d * Math.PI / 180
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * 6371.0088 * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    /** "Commercial>Warehouse>Warehouse" is shown as "Warehouse"; "Commercial> >" as "Commercial". */
    fun categoryName(category: String?): String? =
        category?.split('>')?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }

    /** Which tab a plan row is on: done, late (date passed), today, or this week (and later). */
    fun tab(plan: InspectionPlanDto, today: LocalDate): PlanTab {
        if (plan.state == InspectionState.DONE.name) return PlanTab.DONE
        val date = runCatching { LocalDate.parse(plan.planDate.take(10)) }.getOrNull() ?: return PlanTab.WEEK
        return when {
            date.isBefore(today) -> PlanTab.LATE
            date == today -> PlanTab.TODAY
            else -> PlanTab.WEEK
        }
    }

    /** Plan rows of a tab, in the order to work them: late ones oldest first, the rest by date. */
    fun plansFor(tab: PlanTab, plans: List<InspectionPlanDto>, today: LocalDate, text: String = ""): List<InspectionPlanDto> {
        val q = text.trim().lowercase().filterNot { it == ' ' || it == '-' }
        return plans
            .filter { tab(it, today) == tab }
            .filter { q.isEmpty() || it.searchKey().contains(q) }
            .sortedWith(compareBy({ it.planDate }, { it.propertyCode }, { it.tenantCode }))
    }

    private fun InspectionPlanDto.searchKey() =
        "$propertyCode $tenantCode ${companyName.orEmpty()}".lowercase().filterNot { it == ' ' || it == '-' }

    /** The result each unit has now: recorded in this visit, else from an earlier visit of the same plan row. */
    fun currentResult(unit: InspectionUnitDto, draft: VisitDraft?, plan: InspectionPlanDto): UnitResult? =
        draft?.entries?.get(unit.unitId)?.result
            ?: unit.last?.takeIf { plan.state != InspectionState.NOT_STARTED.name }?.let { runCatching { UnitResult.valueOf(it.result) }.getOrNull() }

    /**
     * FR-034, as the server works it out: DONE when every active unit has a result other than PENDING and
     * none is PENDING; COME_BACK otherwise once visited.
     */
    fun stateAfter(units: List<InspectionUnitDto>, draft: VisitDraft, plan: InspectionPlanDto): InspectionState {
        val results = units.associate { it.unitId to currentResult(it, draft, plan) }
        val activeChecked = units.filter { it.active }.all { results[it.unitId].let { r -> r != null && r != UnitResult.PENDING } }
        val anyPending = results.values.any { it == UnitResult.PENDING }
        return if (activeChecked && !anyPending) InspectionState.DONE else InspectionState.COME_BACK
    }

    /** Count of each result recorded in a visit, for the "Check and send" screen. */
    fun counts(draft: VisitDraft): Map<UnitResult, Int> =
        draft.recorded.groupingBy { it.result!! }.eachCount()

    /** Active units not yet given a result in this visit (nor earlier in the period). */
    fun unchecked(units: List<InspectionUnitDto>, draft: VisitDraft, plan: InspectionPlanDto): List<InspectionUnitDto> =
        units.filter { it.active && currentResult(it, draft, plan).let { r -> r == null || r == UnitResult.PENDING } }
}
