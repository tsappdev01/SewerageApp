package com.meterreading.reader.util

import com.meterreading.reader.platform.localZone
import com.meterreading.reader.platform.twoDigits
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong

// Fixed English formats, the same on Android and iOS whatever the phone's language.

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Readings always use Western digits with thousands separators: 52,500. */
fun formatReading(value: Long): String {
    val digits = abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (value < 0) "-$grouped" else grouped
}

/** Left-pads to the register size, the way the meter's wheels show it: 00120. */
fun registerDigits(value: Long, digits: Int): String = value.toString().padStart(digits, '0').takeLast(digits)

/** "3 Oct" */
fun formatDay(date: LocalDate): String = "${date.dayOfMonth} ${MONTHS[date.monthNumber - 1]}"

/** "07:15" */
fun formatTime(time: LocalDateTime): String = "${time.hour.twoDigits()}:${time.minute.twoDigits()}"

/** Day and time in the phone's zone, e.g. "Sun 07:15", for "list from …". */
fun formatDayTime(instant: Instant): String {
    val t = instant.toLocalDateTime(localZone)
    return "${WEEKDAYS[t.dayOfWeek.ordinal]} ${formatTime(t)}"
}

/** "2026-10-06 07:15", for the stamp on inspection photos. */
fun formatStamp(time: LocalDateTime): String =
    "${time.year}-${time.monthNumber.twoDigits()}-${time.dayOfMonth.twoDigits()} ${formatTime(time)}"

/** [value] with exactly [places] decimals and a dot, e.g. 4.2 → "4.20" (places 2). */
fun formatDecimal(value: Double, places: Int): String {
    var factor = 1L
    repeat(places) { factor *= 10 }
    val scaled = (abs(value) * factor).roundToLong()
    val whole = scaled / factor
    val fraction = (scaled % factor).toString().padStart(places, '0')
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (places == 0) "$sign$whole" else "$sign$whole.$fraction"
}

/**
 * Fills a text template's %1$s / %1$d / %s / %d placeholders, for texts read before they are shown
 * (String.format is not available on every platform).
 */
fun String.fill(vararg args: Any?): String {
    var next = 0
    return Regex("%(?:(\\d+)\\$)?[sd]").replace(this) { m ->
        val index = m.groupValues[1].toIntOrNull()?.minus(1) ?: next++
        args.getOrNull(index)?.toString() ?: m.value
    }
}
