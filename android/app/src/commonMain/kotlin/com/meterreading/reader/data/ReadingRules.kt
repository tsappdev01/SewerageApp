package com.meterreading.reader.data

import com.meterreading.reader.platform.*
/**
 * Local mirror of the server's consumption rules (spec BR-001, BR-006, BR-008).
 * The app only uses this to warn the reader; the server decides.
 */
object ReadingRules {
    /** BR-006: a lower reading is a rollover candidate only within this share of the register maximum. */
    const val ROLLOVER_PROXIMITY_PERCENT = 10

    sealed interface Check {
        val consumption: Long?

        data class Ok(override val consumption: Long) : Check
        data class Rollover(override val consumption: Long) : Check
        data class High(override val consumption: Long) : Check
        data object Lower : Check { override val consumption: Long? = null }
    }

    fun registerMax(digits: Int): Long {
        var max = 1L
        repeat(digits) { max *= 10 }
        return max - 1
    }

    fun check(previous: Long?, current: Long, registerDigits: Int, expectedHigh: Long?): Check {
        val base = previous ?: 0L
        if (current >= base) {
            val consumption = current - base
            return if (expectedHigh != null && consumption > expectedHigh) Check.High(consumption) else Check.Ok(consumption)
        }
        val max = registerMax(registerDigits)
        val nearMax = base >= max - max * ROLLOVER_PROXIMITY_PERCENT / 100
        if (!nearMax) return Check.Lower
        val consumption = (max - base + 1) + current
        return if (expectedHigh != null && consumption > expectedHigh) Check.High(consumption) else Check.Rollover(consumption)
    }
}
