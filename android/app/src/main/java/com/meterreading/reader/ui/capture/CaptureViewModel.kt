package com.meterreading.reader.ui.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meterreading.reader.data.*
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/** State of one meter capture, from the condition picker to the server's answer. */
class CaptureViewModel(private val repo: FakeMeterRepository, meterId: Long) : ViewModel() {
    val meter: Meter = repo.meter(meterId)
    val property: Property = repo.property(meter.propertyCode)

    /** Created once per capture; a retry reuses it (BR-013). */
    private val transactionId = UUID.randomUUID().toString()

    var condition by mutableStateOf(MeterCondition.WORKING)
        private set

    /** -1 is the condition picker; 0.. index into [steps]. */
    var stepIndex by mutableIntStateOf(-1)
        private set

    val steps: List<Step> get() = StatusRules.stepsFor(condition)
    val currentStep: Step? get() = steps.getOrNull(stepIndex)

    val photos = mutableStateMapOf<ImageRole, File>()
    val numbers = mutableStateMapOf<NumberTarget, String>()
    var reasonCode by mutableStateOf<String?>(null)
    var note by mutableStateOf("")
    var newMeterNumber by mutableStateOf("")
    var readerConfirmedWarning by mutableStateOf(false)

    var sending by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<SubmitOutcome?>(null)
        private set
    var nextMeter by mutableStateOf<Meter?>(null)
        private set

    fun chooseCondition(c: MeterCondition) {
        if (c == condition) return
        condition = c
        reasonCode = null
        numbers.clear()
        photos.clear()
        readerConfirmedWarning = false
    }

    fun next() {
        if (stepIndex < steps.lastIndex) stepIndex++
    }

    /** Returns false when there is nothing to go back to and the screen should close. */
    fun back(): Boolean {
        if (outcome != null || sending) return false
        if (stepIndex < 0) return false
        stepIndex--
        return true
    }

    fun digits(target: NumberTarget): String = numbers[target].orEmpty()

    fun typeDigit(target: NumberTarget, c: Char) {
        val current = digits(target)
        if (current.length < meter.registerDigits) numbers[target] = current + c
    }

    fun deleteDigit(target: NumberTarget) {
        numbers[target] = digits(target).dropLast(1)
    }

    fun clearDigits(target: NumberTarget) {
        numbers.remove(target)
    }

    fun skipNumber(target: NumberTarget) {
        numbers.remove(target)
        next()
    }

    fun isComplete(target: NumberTarget): Boolean = digits(target).length == meter.registerDigits

    fun value(target: NumberTarget): Long? = digits(target).takeIf { it.length == meter.registerDigits }?.toLong()

    /** Reading shown as "last time" above the boxes. */
    fun previousFor(target: NumberTarget): Long? = when (target) {
        NumberTarget.CURRENT, NumberTarget.OLD_FINAL -> meter.previousReading
        NumberTarget.NEW_OPENING -> null
        NumberTarget.NEW_CURRENT -> value(NumberTarget.NEW_OPENING)
    }

    fun photoFor(target: NumberTarget): File? = when (target) {
        NumberTarget.CURRENT -> photos[ImageRole.DISPLAY] ?: photos[ImageRole.DAMAGE] ?: photos[ImageRole.CONTEXT]
        NumberTarget.OLD_FINAL -> photos[ImageRole.OLD_METER_FINAL]
        NumberTarget.NEW_OPENING, NumberTarget.NEW_CURRENT -> photos[ImageRole.NEW_METER]
    }

    /** Local warning only (FR-006.6). Opening readings of a new meter are not checked. */
    fun checkFor(target: NumberTarget): ReadingRules.Check? {
        if (target == NumberTarget.NEW_OPENING) return null
        val v = value(target) ?: return null
        val previous = previousFor(target) ?: if (target == NumberTarget.NEW_CURRENT) 0L else null
        return ReadingRules.check(previous, v, meter.registerDigits, meter.expectedHigh)
    }

    fun consumption(): Long? = when (condition) {
        MeterCondition.METER_REPLACED -> {
            val old = checkFor(NumberTarget.OLD_FINAL)?.consumption
            val new = checkFor(NumberTarget.NEW_CURRENT)?.consumption
            if (old != null && new != null) old + new else null
        }
        MeterCondition.NOT_ACCESSIBLE -> null
        else -> checkFor(NumberTarget.CURRENT)?.consumption
    }

    fun submit() {
        if (sending || outcome != null) return
        sending = true
        viewModelScope.launch {
            val draft = ReadingDraft(
                transactionId = transactionId,
                meterId = meter.id,
                condition = condition,
                reasonCode = reasonCode,
                note = note.trim(),
                numbers = NumberTarget.entries.mapNotNull { t -> value(t)?.let { t to it } }.toMap(),
                newMeterNumber = newMeterNumber.trim().ifEmpty { null },
                photoPaths = photos.mapValues { it.value.path },
                readerConfirmedWarning = readerConfirmedWarning,
                capturedAt = LocalDateTime.now(),
            )
            outcome = repo.submit(draft)
            nextMeter = repo.nextMeter(repo.meters.value, after = meter.id)
            sending = false
        }
    }
}
