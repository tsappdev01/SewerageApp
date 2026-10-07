package com.meterreading.reader.data

import com.meterreading.reader.platform.*
import com.meterreading.reader.api.InspectionPlanDto
import com.meterreading.reader.api.InspectionUnitDto
import com.meterreading.reader.api.LastUnitResultDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.datetime.LocalDate

/** Field inspection rules on the phone (spec §21); they mirror the server's InspectionService. */
class InspectionRulesTest {
    private fun entry(result: UnitResult?, occupant: String = "", reasons: List<String> = emptyList(), note: String = "", photos: Int = 0, unitId: String? = "1001", code: String = "1") =
        UnitEntry("r", unitId, code, result = result, occupantName = occupant, reasons = reasons, note = note,
            photos = List(photos) { EvidencePhoto("i$it", "/p$it.jpg", "2026-10-06T08:00:00Z") })

    @Test fun FR032_as_recorded_and_vacant_need_nothing_more() {
        assertNull(InspectionRules.problem(entry(UnitResult.AS_RECORDED)))
        assertNull(InspectionRules.problem(entry(UnitResult.VACANT)))
    }

    @Test fun FR032_no_result_cannot_be_saved() = assertEquals(InspectionRules.Problem.NO_RESULT, InspectionRules.problem(entry(null)))
    @Test fun FR032_subleased_needs_who_is_there() = assertEquals(InspectionRules.Problem.OCCUPANT, InspectionRules.problem(entry(UnitResult.SUBLEASED, photos = 1)))
    @Test fun FR033_subleased_needs_a_photo() = assertEquals(InspectionRules.Problem.PHOTO, InspectionRules.problem(entry(UnitResult.SUBLEASED, occupant = "Al Noor")))
    @Test fun FR032_disputed_needs_a_reason_or_note() = assertEquals(InspectionRules.Problem.REASON, InspectionRules.problem(entry(UnitResult.DISPUTED, photos = 1)))
    @Test fun FR032_rejected_with_reason_and_photo_is_complete() = assertNull(InspectionRules.problem(entry(UnitResult.REJECTED, reasons = listOf("UNSAFE_USE"), photos = 1)))
    @Test fun FR032_pending_needs_a_reason_but_no_photo() = assertNull(InspectionRules.problem(entry(UnitResult.PENDING, note = "Locked")))
    @Test fun FR033_at_most_six_photos() = assertEquals(InspectionRules.Problem.TOO_MANY_PHOTOS, InspectionRules.problem(entry(UnitResult.AS_RECORDED, photos = 7)))
    @Test fun FR035_unit_not_on_list_needs_its_number() = assertEquals(InspectionRules.Problem.UNIT_CODE, InspectionRules.problem(entry(UnitResult.VACANT, unitId = null, code = " ")))

    @Test fun FR031_2_distance_matches_the_server() {
        assertEquals(0.0, InspectionRules.distanceKm(25.0, 55.0, 25.0, 55.0), 1e-9)
        assertEquals(111.2, InspectionRules.distanceKm(25.0, 55.0, 26.0, 55.0), 0.05)
        assertEquals(100.8, InspectionRules.distanceKm(25.0, 55.0, 25.0, 56.0), 0.05)
    }

    @Test fun categories_show_their_last_level() {
        assertEquals("Warehouse", InspectionRules.categoryName("Commercial>Warehouse>Warehouse"))
        assertEquals("Commercial", InspectionRules.categoryName("Commercial> >"))
        assertNull(InspectionRules.categoryName(null))
    }

    private val today = LocalDate.of(2026, 10, 6)
    private fun plan(property: String, date: String, state: String = "NOT_STARTED") =
        InspectionPlanDto("2026-10", date, property, "T", "Company $property", 2, 0, 2, state)

    @Test fun FR031_plan_rows_go_to_today_late_week_or_done() {
        val plans = listOf(plan("A", "2026-10-06"), plan("B", "2026-10-01"), plan("C", "2026-10-09"), plan("D", "2026-10-01", "DONE"), plan("E", "2026-10-03", "COME_BACK"))
        assertEquals(listOf("A"), InspectionRules.plansFor(PlanTab.TODAY, plans, today).map { it.propertyCode })
        assertEquals(listOf("B", "E"), InspectionRules.plansFor(PlanTab.LATE, plans, today).map { it.propertyCode })
        assertEquals(listOf("C"), InspectionRules.plansFor(PlanTab.WEEK, plans, today).map { it.propertyCode })
        assertEquals(listOf("D"), InspectionRules.plansFor(PlanTab.DONE, plans, today).map { it.propertyCode })
        // Search ignores case, spaces and dashes.
        assertEquals(listOf("C"), InspectionRules.plansFor(PlanTab.WEEK, plans, today, "company c").map { it.propertyCode })
    }

    private fun unit(id: String, active: Boolean = true, last: String? = null) =
        InspectionUnitDto(id, "B", id, active = active, last = last?.let { LastUnitResultDto(it, "2026-10-01T08:00:00Z") })

    @Test fun FR034_done_when_every_active_unit_is_checked() {
        val units = listOf(unit("1"), unit("2"), unit("3", active = false))
        val draft = VisitDraft("v", "2026-10", "A", "T", startedAtUtc = "x",
            entries = mapOf("1" to entry(UnitResult.AS_RECORDED, unitId = "1"), "2" to entry(UnitResult.VACANT, unitId = "2")))
        assertEquals(InspectionState.DONE, InspectionRules.stateAfter(units, draft, plan("A", "2026-10-06")))
    }

    @Test fun FR034_a_pending_unit_brings_the_property_back() {
        val units = listOf(unit("1"), unit("2"))
        val draft = VisitDraft("v", "2026-10", "A", "T", startedAtUtc = "x",
            entries = mapOf("1" to entry(UnitResult.AS_RECORDED, unitId = "1"), "2" to entry(UnitResult.PENDING, note = "Locked", unitId = "2")))
        assertEquals(InspectionState.COME_BACK, InspectionRules.stateAfter(units, draft, plan("A", "2026-10-06")))
    }

    @Test fun FR034_results_of_an_earlier_visit_this_period_count() {
        // Unit 1 was checked on the first visit; this visit checks unit 2 only.
        val units = listOf(unit("1", last = "AS_RECORDED"), unit("2", last = "PENDING"))
        val draft = VisitDraft("v", "2026-10", "A", "T", startedAtUtc = "x", entries = mapOf("2" to entry(UnitResult.VACANT, unitId = "2")))
        assertEquals(InspectionState.DONE, InspectionRules.stateAfter(units, draft, plan("A", "2026-10-03", "COME_BACK")))
        assertEquals(0, InspectionRules.unchecked(units, draft, plan("A", "2026-10-03", "COME_BACK")).size)
    }
}
