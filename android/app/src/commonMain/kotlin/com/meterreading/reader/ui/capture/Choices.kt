package com.meterreading.reader.ui.capture

import com.meterreading.reader.platform.*
import org.jetbrains.compose.resources.StringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.meterreading.reader.resources.*
import com.meterreading.reader.data.ImageRole
import com.meterreading.reader.data.MeterCondition
import com.meterreading.reader.data.NumberTarget
import com.meterreading.reader.data.ReasonCategory
import com.meterreading.reader.data.Step
import com.meterreading.reader.ui.theme.AppColors

data class Choice(val code: String, val label: StringResource, val icon: ImageVector, val tint: Color)

/**
 * Pictures and plain words for each code. Codes match the LOVs in spec §11.2.
 * TODO(FR-018): reasons come from GET /config; keep an icon per code here.
 */
object Choices {
    val conditions: Map<MeterCondition, Choice> = linkedMapOf(
        MeterCondition.WORKING to Choice("WORKING", Res.string.cond_working, Icons.Rounded.CheckCircle, AppColors.Ok),
        MeterCondition.DAMAGED to Choice("DAMAGED", Res.string.cond_damaged, Icons.Rounded.BrokenImage, AppColors.Bad),
        MeterCondition.SUBMERSED to Choice("SUBMERSED", Res.string.cond_submersed, Icons.Rounded.Waves, AppColors.Queued),
        MeterCondition.NOT_ACCESSIBLE to Choice("NOT_ACCESSIBLE", Res.string.cond_not_accessible, Icons.Rounded.Lock, AppColors.Warn),
        MeterCondition.METER_REPLACED to Choice("METER_REPLACED", Res.string.cond_replaced, Icons.Rounded.SwapHoriz, AppColors.Taupe),
        MeterCondition.REMOVED to Choice("REMOVED", Res.string.cond_removed, Icons.Rounded.DeleteOutline, AppColors.SubInk),
    )

    fun reasons(category: ReasonCategory): List<Choice> = when (category) {
        ReasonCategory.DAMAGE -> listOf(
            Choice("GLASS_BROKEN", Res.string.r_glass_broken, Icons.Rounded.BrokenImage, AppColors.Bad),
            Choice("LEAKING", Res.string.r_leaking, Icons.Rounded.WaterDrop, AppColors.Queued),
            Choice("NOT_MOVING", Res.string.r_not_moving, Icons.Rounded.HourglassEmpty, AppColors.Warn),
            Choice("OTHER", Res.string.r_other, Icons.Rounded.MoreHoriz, AppColors.SubInk),
        )
        ReasonCategory.ACCESS -> listOf(
            Choice("GATE_LOCKED", Res.string.r_gate_locked, Icons.Rounded.Lock, AppColors.Warn),
            Choice("CAR_ON_TOP", Res.string.r_car_on_top, Icons.Rounded.DirectionsCar, AppColors.Navy),
            Choice("DANGER", Res.string.r_danger, Icons.Rounded.Pets, AppColors.Bad),
            Choice("OWNER_REFUSED", Res.string.r_owner_refused, Icons.Rounded.Block, AppColors.SubInk),
        )
        ReasonCategory.REMOVAL -> listOf(
            Choice("MISSING", Res.string.r_missing, Icons.Rounded.SearchOff, AppColors.Bad),
            Choice("REMOVED_BY_OWNER", Res.string.r_removed_owner, Icons.Rounded.Person, AppColors.SubInk),
            Choice("OTHER", Res.string.r_other, Icons.Rounded.MoreHoriz, AppColors.SubInk),
        )
        ReasonCategory.REPLACEMENT -> listOf(
            Choice("OLD_BROKEN", Res.string.r_old_broken, Icons.Rounded.BrokenImage, AppColors.Bad),
            Choice("PLANNED", Res.string.r_planned, Icons.Rounded.Build, AppColors.Navy),
            Choice("OTHER", Res.string.r_other, Icons.Rounded.MoreHoriz, AppColors.SubInk),
        )
    }
    fun reasonQuestion(category: ReasonCategory): StringResource = when (category) {
        ReasonCategory.DAMAGE -> Res.string.q_reason_damage
        ReasonCategory.ACCESS -> Res.string.q_reason_access
        ReasonCategory.REMOVAL -> Res.string.q_reason_removed
        ReasonCategory.REPLACEMENT -> Res.string.q_reason_replaced
    }
    fun photoHint(role: ImageRole): StringResource = when (role) {
        ImageRole.DISPLAY -> Res.string.photo_hint_display
        ImageRole.CONTEXT -> Res.string.photo_hint_context
        ImageRole.OBSTRUCTION -> Res.string.photo_hint_obstruction
        ImageRole.DAMAGE -> Res.string.photo_hint_damage
        ImageRole.OLD_METER_FINAL -> Res.string.photo_hint_old_final
        ImageRole.NEW_METER -> Res.string.photo_hint_new_meter
    }
    fun numberTitle(target: NumberTarget): StringResource = when (target) {
        NumberTarget.CURRENT -> Res.string.type_number
        NumberTarget.OLD_FINAL -> Res.string.type_old_final
        NumberTarget.NEW_OPENING -> Res.string.type_new_opening
        NumberTarget.NEW_CURRENT -> Res.string.type_new_current
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
