package com.meterreading.reader.ui.inspection

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.meterreading.reader.R
import com.meterreading.reader.data.InspectionState
import com.meterreading.reader.data.UnitResult
import com.meterreading.reader.ui.components.Pill
import com.meterreading.reader.ui.theme.AppColors

/** Icon, word and colours of a result. State is never shown by colour alone. */
data class ResultStyle(val icon: ImageVector, @StringRes val label: Int, @StringRes val hint: Int, val fg: Color, val bg: Color)

fun resultStyle(r: UnitResult): ResultStyle = when (r) {
    UnitResult.AS_RECORDED -> ResultStyle(Icons.Rounded.CheckCircle, R.string.insp_r_as_recorded, R.string.insp_rh_as_recorded, AppColors.Ok, AppColors.OkTint)
    UnitResult.VACANT -> ResultStyle(Icons.Rounded.MeetingRoom, R.string.insp_r_vacant, R.string.insp_rh_vacant, AppColors.Vacant, AppColors.VacantTint)
    UnitResult.SUBLEASED -> ResultStyle(Icons.Rounded.SwapHoriz, R.string.insp_r_subleased, R.string.insp_rh_subleased, AppColors.Sublet, AppColors.SubletTint)
    UnitResult.DISPUTED -> ResultStyle(Icons.Rounded.Warning, R.string.insp_r_disputed, R.string.insp_rh_disputed, AppColors.Warn, AppColors.WarnTint)
    UnitResult.REJECTED -> ResultStyle(Icons.Rounded.Block, R.string.insp_r_rejected, R.string.insp_rh_rejected, AppColors.Bad, AppColors.BadTint)
    UnitResult.PENDING -> ResultStyle(Icons.Rounded.Schedule, R.string.insp_r_pending, R.string.insp_rh_pending, AppColors.SubInk, AppColors.PendingTint)
}

@Composable
fun ResultPill(r: UnitResult) {
    val s = resultStyle(r)
    Pill(stringResource(s.label), s.fg, s.bg, s.icon)
}

@Composable
fun StatePill(state: InspectionState) {
    when (state) {
        InspectionState.NOT_STARTED -> Pill(stringResource(R.string.insp_state_not_started), AppColors.SubInk, AppColors.PendingTint, Icons.Rounded.RadioButtonUnchecked)
        InspectionState.COME_BACK -> Pill(stringResource(R.string.insp_state_come_back), AppColors.Warn, AppColors.WarnTint, Icons.Rounded.Refresh)
        InspectionState.DONE -> Pill(stringResource(R.string.insp_state_done), AppColors.Ok, AppColors.OkTint, Icons.Rounded.CheckCircle)
    }
}

/** Plain words for the reason codes in InspectionRules.reasonsFor. */
@StringRes
fun reasonLabel(code: String): Int = when (code) {
    "OTHER_COMPANY_SIGN" -> R.string.insp_rs_other_company_sign
    "STAFF_SAY_SO" -> R.string.insp_rs_staff_say_so
    "LICENCE_SHOWN" -> R.string.insp_rs_licence_shown
    "DIFFERENT_TRADE" -> R.string.insp_rs_different_trade
    "TENANT_DISAGREES" -> R.string.insp_rs_tenant_disagrees
    "RECORD_WRONG" -> R.string.insp_rs_record_wrong
    "UNIT_SPLIT" -> R.string.insp_rs_unit_split
    "LABOUR_IN_WAREHOUSE" -> R.string.insp_rs_labour_in_warehouse
    "UNSAFE_USE" -> R.string.insp_rs_unsafe_use
    "TRADE_NOT_ALLOWED" -> R.string.insp_rs_trade_not_allowed
    "LOCKED" -> R.string.insp_rs_locked
    "NO_ACCESS" -> R.string.insp_rs_no_access
    "REFUSED" -> R.string.insp_rs_refused
    "COME_BACK_LATER" -> R.string.insp_rs_come_back_later
    "EMPTY" -> R.string.insp_rs_empty
    "BEING_FITTED_OUT" -> R.string.insp_rs_being_fitted_out
    else -> R.string.insp_rs_other
}

@StringRes
fun problemLabel(p: com.meterreading.reader.data.InspectionRules.Problem): Int = when (p) {
    com.meterreading.reader.data.InspectionRules.Problem.NO_RESULT -> R.string.insp_p_result
    com.meterreading.reader.data.InspectionRules.Problem.UNIT_CODE -> R.string.insp_p_unit_code
    com.meterreading.reader.data.InspectionRules.Problem.OCCUPANT -> R.string.insp_p_occupant
    com.meterreading.reader.data.InspectionRules.Problem.REASON -> R.string.insp_p_reason
    com.meterreading.reader.data.InspectionRules.Problem.PHOTO -> R.string.insp_p_photo
    com.meterreading.reader.data.InspectionRules.Problem.TOO_MANY_PHOTOS -> R.string.insp_p_too_many
    com.meterreading.reader.data.InspectionRules.Problem.PEOPLE -> R.string.insp_p_people
}

/** Types offered for a unit not on the list, as category paths like the unit view's. */
val newUnitTypes: List<Pair<Int, String>> = listOf(
    R.string.insp_t_warehouse to "Industrial>Warehouse>Warehouse",
    R.string.insp_t_shop to "Commercial>Shop>Shop",
    R.string.insp_t_office to "Commercial>Office>Office",
    R.string.insp_t_labour to "Residential>Labor Camps>Room in labor camp",
    R.string.insp_t_showroom to "Commercial>Showroom>Showroom",
    R.string.insp_t_workshop to "Industrial>Workshop>Workshop",
)
