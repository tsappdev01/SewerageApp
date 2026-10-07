package com.meterreading.reader.data

import com.meterreading.reader.platform.*
enum class NumberTarget { CURRENT, OLD_FINAL, NEW_OPENING, NEW_CURRENT }

/** One screen in the capture flow after the condition picker. */
sealed interface Step {
    data class Reason(val category: ReasonCategory) : Step
    data class Photo(val role: ImageRole) : Step
    data class Number(val target: NumberTarget, val required: Boolean) : Step
    data class Note(val required: Boolean) : Step
    data object NewMeterNumber : Step
    data object Confirm : Step
}

/**
 * Steps per meter condition, mirroring spec §6.1.
 * TODO(FR-006.5): replace with the StatusRules the server sends in GET /config.
 */
object StatusRules {
    fun stepsFor(condition: MeterCondition): List<Step> = when (condition) {
        MeterCondition.WORKING -> listOf(
            Step.Photo(ImageRole.DISPLAY),
            Step.Number(NumberTarget.CURRENT, required = true),
            Step.Confirm,
        )
        MeterCondition.DAMAGED -> listOf(
            Step.Reason(ReasonCategory.DAMAGE),
            Step.Photo(ImageRole.DAMAGE),
            Step.Number(NumberTarget.CURRENT, required = false),
            Step.Note(required = true),
            Step.Confirm,
        )
        MeterCondition.SUBMERSED -> listOf(
            Step.Photo(ImageRole.CONTEXT),
            Step.Number(NumberTarget.CURRENT, required = false),
            Step.Note(required = true),
            Step.Confirm,
        )
        MeterCondition.NOT_ACCESSIBLE -> listOf(
            Step.Reason(ReasonCategory.ACCESS),
            Step.Photo(ImageRole.OBSTRUCTION),
            Step.Note(required = true),
            Step.Confirm,
        )
        MeterCondition.METER_REPLACED -> listOf(
            Step.Reason(ReasonCategory.REPLACEMENT),
            Step.Photo(ImageRole.OLD_METER_FINAL),
            Step.Number(NumberTarget.OLD_FINAL, required = true),
            Step.Photo(ImageRole.NEW_METER),
            Step.NewMeterNumber,
            Step.Number(NumberTarget.NEW_OPENING, required = true),
            Step.Number(NumberTarget.NEW_CURRENT, required = true),
            Step.Confirm,
        )
        MeterCondition.REMOVED -> listOf(
            Step.Reason(ReasonCategory.REMOVAL),
            Step.Photo(ImageRole.CONTEXT),
            Step.Number(NumberTarget.CURRENT, required = false),
            Step.Note(required = true),
            Step.Confirm,
        )
    }

    /** Conditions the server always sends to a supervisor (spec §7.2). */
    val alwaysChecked = setOf(MeterCondition.DAMAGED, MeterCondition.METER_REPLACED, MeterCondition.REMOVED)
}
