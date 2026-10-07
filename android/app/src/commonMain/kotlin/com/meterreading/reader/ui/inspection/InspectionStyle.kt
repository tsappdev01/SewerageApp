package com.meterreading.reader.ui.inspection

import com.meterreading.reader.platform.*
import org.jetbrains.compose.resources.StringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.stringResource
import com.meterreading.reader.resources.*
import com.meterreading.reader.data.InspectionState
import com.meterreading.reader.data.UnitResult
import com.meterreading.reader.ui.components.Pill
import com.meterreading.reader.ui.theme.AppColors

/** Icon, word and colours of a result. State is never shown by colour alone. */
data class ResultStyle(val icon: ImageVector, val label: StringResource, val hint: StringResource, val fg: Color, val bg: Color)

fun resultStyle(r: UnitResult): ResultStyle = when (r) {
    UnitResult.AS_RECORDED -> ResultStyle(Icons.Rounded.CheckCircle, Res.string.insp_r_as_recorded, Res.string.insp_rh_as_recorded, AppColors.Ok, AppColors.OkTint)
    UnitResult.VACANT -> ResultStyle(Icons.Rounded.MeetingRoom, Res.string.insp_r_vacant, Res.string.insp_rh_vacant, AppColors.Vacant, AppColors.VacantTint)
    UnitResult.SUBLEASED -> ResultStyle(Icons.Rounded.SwapHoriz, Res.string.insp_r_subleased, Res.string.insp_rh_subleased, AppColors.Sublet, AppColors.SubletTint)
    UnitResult.DISPUTED -> ResultStyle(Icons.Rounded.Warning, Res.string.insp_r_disputed, Res.string.insp_rh_disputed, AppColors.Warn, AppColors.WarnTint)
    UnitResult.REJECTED -> ResultStyle(Icons.Rounded.Block, Res.string.insp_r_rejected, Res.string.insp_rh_rejected, AppColors.Bad, AppColors.BadTint)
    UnitResult.PENDING -> ResultStyle(Icons.Rounded.Schedule, Res.string.insp_r_pending, Res.string.insp_rh_pending, AppColors.SubInk, AppColors.PendingTint)
}

@Composable
fun ResultPill(r: UnitResult) {
    val s = resultStyle(r)
    Pill(stringResource(s.label), s.fg, s.bg, s.icon)
}

@Composable
fun StatePill(state: InspectionState) {
    when (state) {
        InspectionState.NOT_STARTED -> Pill(stringResource(Res.string.insp_state_not_started), AppColors.SubInk, AppColors.PendingTint, Icons.Rounded.RadioButtonUnchecked)
        InspectionState.COME_BACK -> Pill(stringResource(Res.string.insp_state_come_back), AppColors.Warn, AppColors.WarnTint, Icons.Rounded.Refresh)
        InspectionState.DONE -> Pill(stringResource(Res.string.insp_state_done), AppColors.Ok, AppColors.OkTint, Icons.Rounded.CheckCircle)
    }
}

/** Plain words for the reason codes in InspectionRules.reasonsFor. */
fun reasonLabel(code: String): StringResource = when (code) {
    "OTHER_COMPANY_SIGN" -> Res.string.insp_rs_other_company_sign
    "STAFF_SAY_SO" -> Res.string.insp_rs_staff_say_so
    "LICENCE_SHOWN" -> Res.string.insp_rs_licence_shown
    "DIFFERENT_TRADE" -> Res.string.insp_rs_different_trade
    "TENANT_DISAGREES" -> Res.string.insp_rs_tenant_disagrees
    "RECORD_WRONG" -> Res.string.insp_rs_record_wrong
    "UNIT_SPLIT" -> Res.string.insp_rs_unit_split
    "LABOUR_IN_WAREHOUSE" -> Res.string.insp_rs_labour_in_warehouse
    "UNSAFE_USE" -> Res.string.insp_rs_unsafe_use
    "TRADE_NOT_ALLOWED" -> Res.string.insp_rs_trade_not_allowed
    "LOCKED" -> Res.string.insp_rs_locked
    "NO_ACCESS" -> Res.string.insp_rs_no_access
    "REFUSED" -> Res.string.insp_rs_refused
    "COME_BACK_LATER" -> Res.string.insp_rs_come_back_later
    "EMPTY" -> Res.string.insp_rs_empty
    "BEING_FITTED_OUT" -> Res.string.insp_rs_being_fitted_out
    else -> Res.string.insp_rs_other
}
fun problemLabel(p: com.meterreading.reader.data.InspectionRules.Problem): StringResource = when (p) {
    com.meterreading.reader.data.InspectionRules.Problem.NO_RESULT -> Res.string.insp_p_result
    com.meterreading.reader.data.InspectionRules.Problem.UNIT_CODE -> Res.string.insp_p_unit_code
    com.meterreading.reader.data.InspectionRules.Problem.OCCUPANT -> Res.string.insp_p_occupant
    com.meterreading.reader.data.InspectionRules.Problem.REASON -> Res.string.insp_p_reason
    com.meterreading.reader.data.InspectionRules.Problem.PHOTO -> Res.string.insp_p_photo
    com.meterreading.reader.data.InspectionRules.Problem.TOO_MANY_PHOTOS -> Res.string.insp_p_too_many
    com.meterreading.reader.data.InspectionRules.Problem.PEOPLE -> Res.string.insp_p_people
}

/** Types offered for a unit not on the list, as category paths like the unit view's. */
val newUnitTypes: List<Pair<Int, String>> = listOf(
    Res.string.insp_t_warehouse to "Industrial>Warehouse>Warehouse",
    Res.string.insp_t_shop to "Commercial>Shop>Shop",
    Res.string.insp_t_office to "Commercial>Office>Office",
    Res.string.insp_t_labour to "Residential>Labor Camps>Room in labor camp",
    Res.string.insp_t_showroom to "Commercial>Showroom>Showroom",
    Res.string.insp_t_workshop to "Industrial>Workshop>Workshop",
)
