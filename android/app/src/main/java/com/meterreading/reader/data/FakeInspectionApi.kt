package com.meterreading.reader.data

import com.meterreading.reader.api.InspectionImageResponse
import com.meterreading.reader.api.InspectionPlanDto
import com.meterreading.reader.api.InspectionPlanListDto
import com.meterreading.reader.api.InspectionUnitDto
import com.meterreading.reader.api.InspectionUnitsDto
import com.meterreading.reader.api.SubmitInspectionRequest
import com.meterreading.reader.api.SubmitInspectionResponse
import kotlinx.coroutines.delay
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/** Sample inspection plan for demo builds (USE_FAKE_DATA), shaped like db/dev/000. [online] is the demo's signal switch. */
class FakeInspectionApi(private val online: () -> Boolean = { true }) : InspectionApi {
    override val baseUrl: String = "demo"
    override val devUser: String = "demo"

    private val today = LocalDate.now()
    private val stored = mutableSetOf<String>()

    private fun plan(property: String, tenant: String, company: String, days: Long, active: Int, inactive: Int) =
        InspectionPlanDto("${today.year}-%02d".format(today.monthValue), today.plusDays(days).toString(), property, tenant, company, active, inactive, active + inactive)

    private val plans = listOf(
        plan("597-559", "T-0559", "Elegant Industries LLC", 0, 5, 0),
        plan("597-972", "T-0972", "Danway Electrical and Mechanical Engineering LLC", -3, 4, 0),
        plan("598-1187", "T-1187", "Technical Supplies and Services Co (LLC)", 2, 2, 3),
    )

    private fun unit(id: String, building: String, code: String, category: String, sub: String?, active: Boolean = true) =
        InspectionUnitDto(id, building, code, category, InspectionRules.categoryName(category), sub, active)

    private val units = mapOf(
        "597-559" to (1..5).map { unit("100$it", "ELEGANT INDUSTRIES", "$it", "Industrial>Warehouse>Warehouse", "GURCOAT GARAGE L.L.C") },
        "597-972" to listOf("003", "005", "006", "007").mapIndexed { i, c ->
            unit("200${i + 1}", "DANWAY LABOUR", c, "Residential>Labor Camps>Room in labor camp", "DANWAY ELECTRICAL AND MECHANICAL ENGINEERING L.L.C")
        },
        "598-1187" to listOf(
            unit("3001", "TECHNICAL SUPPLIES", "G01", "Commercial> >", "TECHNICAL SUPPLIES AND SERVICES CO"),
            unit("3002", "TECHNICAL SUPPLIES", "G02", "Commercial> >", "TECHNICAL SUPPLIES AND SERVICES CO"),
            unit("3003", "TECHNICAL SUPPLIES", "F01", "Commercial>Shop>Shop", null, active = false),
            unit("3004", "TECHNICAL SUPPLIES", "F02", "Commercial>Shop>Shop", null, active = false),
            unit("3005", "TECHNICAL SUPPLIES", "F03", "Commercial>Shop>Shop", null, active = false),
        ),
    )

    private fun check() {
        if (!online()) throw IOException("demo: no signal")
    }

    override suspend fun inspectionPlan(): InspectionPlanListDto {
        check()
        delay(300)
        return InspectionPlanListDto(today.toString(), today.minusDays(60).toString(), today.plusDays(14).toString(), plans, Instant.now().toString())
    }

    override suspend fun inspectionUnits(periodCode: String, propertyCode: String, tenantCode: String): InspectionUnitsDto {
        check()
        return InspectionUnitsDto(plans.first { it.propertyCode == propertyCode }, units[propertyCode].orEmpty())
    }

    override suspend fun submitInspection(request: SubmitInspectionRequest): SubmitInspectionResponse {
        check()
        delay(600)
        stored += request.visitId
        val flagged = request.units.count { it.result in InspectionRules.flagged.map { f -> f.name } }
        val pending = request.units.any { it.result == UnitResult.PENDING.name }
        return SubmitInspectionResponse(request.visitId, if (pending) "COME_BACK" else "DONE", request.units.size, flagged, request.units.sumOf { it.photoCount })
    }

    override suspend fun uploadInspectionPhoto(
        visitId: String, imageId: String, resultId: String?, capturedAtUtc: String, bytes: ByteArray, sha256Hex: String,
    ): InspectionImageResponse {
        check()
        return InspectionImageResponse(imageId, visitId, resultId, if (resultId == null) "SIGNATURE" else "EVIDENCE", bytes.size, sha256Hex)
    }
}
