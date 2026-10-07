package com.meterreading.reader.platform

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Shared code runs on Android and iOS, so it uses kotlinx-datetime and kotlin.time instead of
// java.time. These helpers keep the java.time names the code was written with.

/** The phone's own time zone. */
val localZone: TimeZone get() = TimeZone.currentSystemDefault()

fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

fun Instant.Companion.ofEpochMilli(millis: Long): Instant = fromEpochMilliseconds(millis)
fun Instant.toEpochMilli(): Long = toEpochMilliseconds()
fun Instant.isBefore(other: Instant): Boolean = this < other
fun Instant.isAfter(other: Instant): Boolean = this > other
fun Instant.minusSeconds(seconds: Long): Instant = this - seconds.seconds
fun Instant.plusSeconds(seconds: Long): Instant = this + seconds.seconds

fun LocalDate.Companion.now(zone: TimeZone = localZone): LocalDate = Clock.System.todayIn(zone)
fun LocalDate.Companion.of(year: Int, month: Int, day: Int): LocalDate = LocalDate(year, month, day)
fun LocalDate.plusDays(days: Long): LocalDate = plus(days.toInt(), DateTimeUnit.DAY)
fun LocalDate.minusDays(days: Long): LocalDate = minus(days.toInt(), DateTimeUnit.DAY)
fun LocalDate.isBefore(other: LocalDate): Boolean = this < other
fun LocalDate.isAfter(other: LocalDate): Boolean = this > other
val LocalDate.monthValue: Int get() = monthNumber

fun LocalDateTime.Companion.now(zone: TimeZone = localZone): LocalDateTime = Clock.System.now().toLocalDateTime(zone)
fun LocalDateTime.Companion.of(year: Int, month: Int, day: Int, hour: Int, minute: Int): LocalDateTime =
    LocalDateTime(year, month, day, hour, minute)
fun LocalDateTime.Companion.ofInstant(instant: Instant, zone: TimeZone): LocalDateTime = instant.toLocalDateTime(zone)
fun LocalDateTime.plusMinutes(minutes: Long): LocalDateTime =
    (toInstant(TimeZone.UTC) + minutes.minutes).toLocalDateTime(TimeZone.UTC)
fun LocalDateTime.isBefore(other: LocalDateTime): Boolean = this < other
fun LocalDateTime.isAfter(other: LocalDateTime): Boolean = this > other
fun LocalDateTime.toLocalDate(): LocalDate = date
/** The instant this wall-clock time names in [zone] (java.time's atZone(zone).toInstant()). */
fun LocalDateTime.atZoneInstant(zone: TimeZone): Instant = toInstant(zone)

fun Duration.Companion.between(start: Instant, end: Instant): Duration = end - start
fun Duration.Companion.ofDays(days: Long): Duration = days.days
fun Duration.Companion.ofMinutes(minutes: Long): Duration = minutes.minutes
fun Duration.Companion.ofSeconds(seconds: Long): Duration = seconds.seconds
fun Duration.Companion.ofMillis(millis: Long): Duration = millis.milliseconds
fun Duration.toMillis(): Long = inWholeMilliseconds

/** A random id in the usual form, e.g. 3f1c2b4a-1111-4222-8333-444455556666. */
@OptIn(ExperimentalUuidApi::class)
fun randomUuid(): String = Uuid.random().toString()

/** Two digits, e.g. 7 → "07" (String.format is not available on every platform). */
fun Int.twoDigits(): String = toString().padStart(2, '0')

fun LocalDateTime.minusMinutes(minutes: Long): LocalDateTime = plusMinutes(-minutes)
fun Duration.Companion.ofHours(hours: Long): Duration = hours.hours
