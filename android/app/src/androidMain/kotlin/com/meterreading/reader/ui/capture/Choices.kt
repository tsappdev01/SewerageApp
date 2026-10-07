package com.meterreading.reader.ui.capture

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.meterreading.reader.R
import com.meterreading.reader.data.ImageRole
import com.meterreading.reader.data.MeterCondition
import com.meterreading.reader.data.NumberTarget
import com.meterreading.reader.data.ReasonCategory
import com.meterreading.reader.data.Step
import com.meterreading.reader.ui.theme.AppColors

data class Choice(val code: String, @StringRes val label: Int, val icon: ImageVector, val tint: Color)

/**
 * Pictures and plain words for each code. Codes match the LOVs in spec §11.2.
 * TODO(FR-018): reasons come from GET /config; keep an icon per code here.
 */
object Choices {
    val conditions: Map<MeterCondition, Choice> = linkedMapOf(
        MeterCondition.WORKING to Choice("WORKING", R.string.cond_working, Icons.Rounded.CheckCircle, AppColors.Ok),
        MeterCondition.DAMAGED to Choice("DAMAGED", R.string.cond_damaged, Icons.Rounded.BrokenImage, AppColors.Bad),
        MeterCondition.SUBMERSED to Choice("SUBMERSED", R.string.cond_submersed, Icons.Rounded.Waves, AppColors.Queued),
        MeterCondition.NOT_ACCESSIBLE to Choice("NOT_ACCESSIBLE", R.string.cond_not_accessible, Icons.Rounded.Lock, AppColors.Warn),
        MeterCondition.METER_REPLACED to Choice("METER_REPLACED", R.string.cond_replaced, Icons.Rounded.SwapHoriz, AppColors.Taupe),
        MeterCondition.REMOVED to Choice("REMOVED", R.string.cond_removed, Icons.Rounded.DeleteOutline, AppColors.SubInk),
    )

    fun reasons(category: ReasonCategory): List<Choice> = when (category) {
        ReasonCategory.DAMAGE -> listOf(
            Choice("GLASS_BROKEN", R.string.r_glass_broken, Icons.Rounded.BrokenImage, AppColors.Bad),
            Choice("LEAKING", R.string.r_leaking, Icons.Rounded.WaterDrop, AppColors.Queued),
            Choice("NOT_MOVING", R.string.r_not_moving, Icons.Rounded.HourglassEmpty, AppColors.Warn),
            Choice("OTHER", R.string.r_other, Icons.Rounded.MoreHoriz, AppColors.SubInk),
        )
        ReasonCategory.ACCESS -> listOf(
            Choice("GATE_LOCKED", R.string.r_gate_locked, Icons.Rounded.Lock, AppColors.Warn),
            Choice("CAR_ON_TOP", R.string.r_car_on_top, Icons.Rounded.DirectionsCar, AppColors.Navy),
            Choice("DANGER", R.string.r_danger, Icons.Rounded.Pets, AppColors.Bad),
            Choice("OWNER_REFUSED", R.string.r_owner_refused, Icons.Rounded.Block, AppColors.SubInk),
        )
        ReasonCategory.REMOVAL -> listOf(
            Choice("MISSING", R.string.r_missing, Icons.Rounded.SearchOff, AppColors.Bad),
            Choice("REMOVED_BY_OWNER", R.string.r_removed_owner, Icons.Rounded.Person, AppColors.SubInk),
            Choice("OTHER", R.string.r_other, Icons.Rounded.MoreHoriz, AppColors.SubInk),
        )
        ReasonCategory.REPLACEMENT -> listOf(
            Choice("OLD_BROKEN", R.string.r_old_broken, Icons.Rounded.BrokenImage, AppColors.Bad),
            Choice("PLANNED", R.string.r_planned, Icons.Rounded.Build, AppColors.Navy),
            Choice("OTHER", R.string.r_other, Icons.Rounded.MoreHoriz, AppColors.SubInk),
        )
    }

    @StringRes
    fun reasonQuestion(category: ReasonCategory): Int = when (category) {
        ReasonCategory.DAMAGE -> R.string.q_reason_damage
        ReasonCategory.ACCESS -> R.string.q_reason_access
        ReasonCategory.REMOVAL -> R.string.q_reason_removed
        ReasonCategory.REPLACEMENT -> R.string.q_reason_replaced
    }

    @StringRes
    fun photoHint(role: ImageRole): Int = when (role) {
        ImageRole.DISPLAY -> R.string.photo_hint_display
        ImageRole.CONTEXT -> R.string.photo_hint_context
        ImageRole.OBSTRUCTION -> R.string.photo_hint_obstruction
        ImageRole.DAMAGE -> R.string.photo_hint_damage
        ImageRole.OLD_METER_FINAL -> R.string.photo_hint_old_final
        ImageRole.NEW_METER -> R.string.photo_hint_new_meter
    }

    @StringRes
    fun numberTitle(target: NumberTarget): Int = when (target) {
        NumberTarget.CURRENT -> R.string.type_number
        NumberTarget.OLD_FINAL -> R.string.type_old_final
        NumberTarget.NEW_OPENING -> R.string.type_new_opening
        NumberTarget.NEW_CURRENT -> R.string.type_new_current
    }

    fun stepIcon(step: Step?): ImageVector = when (step) {
        null -> Icons.Rounded.Speed
        is Step.Reason -> Icons.Rounded.Checklist
        is Step.Photo -> Icons.Rounded.PhotoCamera
        is Step.Number -> Icons.Rounded.Pin
        is Step.Note -> Icons.Rounded.Mic
        Step.NewMeterNumber -> Icons.Rounded.Edit
        Step.Confirm -> Icons.Rounded.CheckCircle
    }
}
