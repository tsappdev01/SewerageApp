package com.meterreading.reader.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * In-memory stand-in for the API and the offline database, so the UI can be built and
 * tried with readers before the backend exists. Replace with the real repository
 * (Room + SQLCipher outbox, Retrofit) behind the same functions.
 */
class FakeMeterRepository {
    val readerName = "Rashid"

    private val properties = listOf(
        Property("1100", "Villa 1100", "597", 1),
        Property("1101", "Villa 1101", "597", 2),
        Property("1499-W1", "Building 1499-W1", "598", 1),
        Property("1502", "Villa 1502", "598", 2),
        Property("1497", "Villa 1497", "598", 3),
        Property("3010", "Villa 3010", "602", 1),
    )
    private val propertyByCode = properties.associateBy { it.code }

    private val _meters = MutableStateFlow(seedMeters())
    val meters: StateFlow<List<Meter>> = _meters.asStateFlow()

    private val _readings = MutableStateFlow(seedReadings(_meters.value))
    val readings: StateFlow<List<Reading>> = _readings.asStateFlow()

    /** Demo switch: when false, readings are saved on the phone as if there were no signal. */
    val online = MutableStateFlow(true)

    fun meter(id: Long): Meter = _meters.value.first { it.id == id }

    fun property(code: String): Property = propertyByCode.getValue(code)

    fun zoneProgress(meters: List<Meter>): List<ZoneProgress> =
        meters.groupBy { it.zoneCode }
            .map { (code, list) -> ZoneProgress(code, list.size, list.count { !it.state.canCapture }) }
            .sortedWith(compareBy<ZoneProgress>({ it.done == it.total }, { it.code }))

    fun propertiesIn(zoneCode: String, meters: List<Meter>): List<PropertyProgress> =
        properties.filter { it.zoneCode == zoneCode }
            .sortedBy { it.route }
            .map { p -> PropertyProgress(p, meters.filter { it.propertyCode == p.code }.sortedBy { it.route }) }
            .sortedBy { it.done == it.meters.size }

    /** Every property with its meters, in route order, for search. */
    fun allProperties(meters: List<Meter>): List<PropertyProgress> =
        properties.sortedWith(compareBy<Property>({ it.zoneCode }, { it.route }))
            .map { p -> PropertyProgress(p, meters.filter { it.propertyCode == p.code }.sortedBy { it.route }) }

    fun metersAt(propertyCode: String, meters: List<Meter>): List<Meter> =
        meters.filter { it.propertyCode == propertyCode }.sortedBy { it.route }

    /** Next meter in route order; stays in the same building when it can, revisits last. */
    fun nextMeter(meters: List<Meter>, after: Long? = null): Meter? {
        val afterProperty = after?.let { id -> meters.firstOrNull { it.id == id }?.propertyCode }
        val candidates = meters
            .filter { it.state.canCapture && it.id != after }
            .sortedWith(
                compareBy<Meter>(
                    { it.state == ReadingState.REVISIT },
                    { it.zoneCode },
                    { propertyByCode[it.propertyCode]?.route ?: 0 },
                    { it.route },
                ),
            )
        return candidates.firstOrNull { it.propertyCode == afterProperty && it.state != ReadingState.REVISIT }
            ?: candidates.firstOrNull()
    }

    suspend fun submit(draft: ReadingDraft): SubmitOutcome {
        delay(700)
        val needsCheck = draft.readerConfirmedWarning || draft.condition in StatusRules.alwaysChecked
        val outcome = when {
            !online.value -> SubmitOutcome.QUEUED
            needsCheck -> SubmitOutcome.CHECKING
            else -> SubmitOutcome.SENT
        }
        val reading = Reading(
            transactionId = draft.transactionId,
            meterId = draft.meterId,
            condition = draft.condition,
            value = draft.value,
            capturedAt = draft.capturedAt,
            state = outcome.toState(),
            needsCheck = needsCheck,
        )
        _readings.update { listOf(reading) + it }
        _meters.update { list ->
            list.map { m ->
                if (m.id == draft.meterId) m.copy(state = meterState(draft.condition, reading.state), supervisorNote = null) else m
            }
        }
        return outcome
    }

    /** Sends everything saved on the phone. Returns how many were sent. */
    suspend fun sendQueued(): Int {
        val queued = _readings.value.filter { it.state == ReadingState.QUEUED }
        if (queued.isEmpty() || !online.value) return 0
        delay(800)
        val resolved = queued.associate { r ->
            r.transactionId to if (r.needsCheck) ReadingState.CHECKING else ReadingState.SENT
        }
        _readings.update { list -> list.map { r -> resolved[r.transactionId]?.let { r.copy(state = it) } ?: r } }
        _meters.update { list ->
            list.map { m ->
                val r = queued.firstOrNull { it.meterId == m.id }
                if (r != null && m.state == ReadingState.QUEUED) {
                    m.copy(state = meterState(r.condition, resolved.getValue(r.transactionId)))
                } else {
                    m
                }
            }
        }
        return queued.size
    }

    /** BR-009: a meter that could not be reached gets a revisit once the server has it. */
    private fun meterState(condition: MeterCondition, readingState: ReadingState): ReadingState =
        if (condition == MeterCondition.NOT_ACCESSIBLE && readingState != ReadingState.QUEUED) ReadingState.REVISIT else readingState

    private fun SubmitOutcome.toState(): ReadingState = when (this) {
        SubmitOutcome.SENT -> ReadingState.SENT
        SubmitOutcome.QUEUED -> ReadingState.QUEUED
        SubmitOutcome.CHECKING -> ReadingState.CHECKING
    }

    private fun seedMeters(): List<Meter> {
        val lastRead = LocalDate.of(2026, 9, 3)
        fun m(
            id: Long, number: String, type: MeterType, property: String, zone: String, route: Int,
            previous: Long?, state: ReadingState = ReadingState.PENDING, note: String? = null,
        ) = Meter(
            id = id, number = number, type = type, propertyCode = property, zoneCode = zone, route = route,
            registerDigits = 5, previousReading = previous, previousDate = previous?.let { lastRead },
            expectedHigh = if (type == MeterType.IRRIGATION) 3_000 else 900,
            state = state, supervisorNote = note,
        )
        val i = MeterType.IRRIGATION
        val s = MeterType.SEWERAGE
        return listOf(
            m(1, "1001-I", i, "1100", "597", 1, 49_820, ReadingState.SENT),
            m(2, "1001-S", s, "1100", "597", 2, 8_150, ReadingState.SENT),
            m(3, "1101-I", i, "1101", "597", 1, 30_110),
            m(4, "1101-S", s, "1101", "597", 2, 99_950), // rollover example (BR-006)
            m(5, "2001-I", i, "1499-W1", "598", 1, 61_200, ReadingState.SENT),
            m(6, "2001-2", i, "1499-W1", "598", 2, 52_500),
            m(7, "2002-2", s, "1499-W1", "598", 3, 17_040),
            m(8, "2002-3", s, "1499-W1", "598", 4, 22_310, ReadingState.READ_AGAIN, "Photo not clear"),
            m(9, "2002-4", s, "1499-W1", "598", 5, 5_120),
            m(10, "2002-5", s, "1499-W1", "598", 6, null), // first reading of a new meter (BR-004)
            m(11, "2002-6", s, "1499-W1", "598", 7, 40_400),
            m(12, "1502-I", i, "1502", "598", 1, 12_000),
            m(13, "1502-S", s, "1502", "598", 2, 3_300),
            m(14, "1497-I", i, "1497", "598", 1, 7_450, ReadingState.SENT),
            m(15, "1497-S", s, "1497", "598", 2, 2_010, ReadingState.SENT),
            m(16, "3010-I", i, "3010", "602", 1, 15_600, ReadingState.SENT),
            m(17, "3010-S", s, "3010", "602", 2, 4_480, ReadingState.SENT),
        )
    }

    private fun seedReadings(meters: List<Meter>): List<Reading> {
        val start = LocalDateTime.of(2026, 10, 4, 6, 40)
        return meters.filter { it.state == ReadingState.SENT }.mapIndexed { index, m ->
            Reading(
                transactionId = UUID.randomUUID().toString(),
                meterId = m.id,
                condition = MeterCondition.WORKING,
                value = (m.previousReading ?: 0) + 250 + index * 37,
                capturedAt = start.plusMinutes(index * 6L),
                state = ReadingState.SENT,
                needsCheck = false,
            )
        }.reversed()
    }
}

/** Single place the screens get their data from until dependency injection (Hilt) is added. */
object AppGraph {
    val repository: FakeMeterRepository by lazy { FakeMeterRepository() }
}
