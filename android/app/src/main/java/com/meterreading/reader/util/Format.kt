package com.meterreading.reader.util

import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val readingFormat = NumberFormat.getIntegerInstance(Locale.US)
private val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

/** Readings always use Western digits with thousands separators: 52,500. */
fun formatReading(value: Long): String = readingFormat.format(value)

/** Left-pads to the register size, the way the meter's wheels show it: 00120. */
fun registerDigits(value: Long, digits: Int): String = value.toString().padStart(digits, '0').takeLast(digits)

fun formatDay(date: LocalDate): String = dayFormat.format(date)

fun formatTime(time: LocalDateTime): String = timeFormat.format(time)
