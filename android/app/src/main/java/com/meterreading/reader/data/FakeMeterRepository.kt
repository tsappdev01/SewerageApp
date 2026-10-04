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
 * In-memory sample data, so the app can be shown and tried with readers without a server.
 * Used when the build sets USE_FAKE_DATA.
 */
class FakeMeterRepository : MeterRepository() {
    override val readerName: StateFlow<String> = MutableStateFlow("Rashid")

    override val properties: StateFlow<List<Property>> = MutableStateFlow(listOf(
        Property("1100", "1100", "597", 1, "T-0101", "Palmgate Foods Trading", listOf(Tenant("T-0101", "Palmgate Foods Trading"))),
        Property(
            "1101", "1101", "597", 2, "T-0102", "Crescent Fabrication LLC",
            listOf(Tenant("T-0102", "Crescent Fabrication LLC"), Tenant("T-0199", "Crescent Fabrication (Old Lease)")),
        ),
        Property("1499-W1", "1499-W1", "598", 1, "T-0201", "Sandline Logistics LLC", listOf(Tenant("T-0201", "Sandline Logistics LLC"))),
        Property("1502", "1502", "598", 2, "T-0202", "Bluewave Packaging", listOf(Tenant("T-0202", "Bluewave Packaging"))),
        Property("1497", "1497", "598", 3, "T-0203", "Oasis Cold Store", listOf(Tenant("T-0203", "Oasis Cold Store"))),
        Property("3010", "3010", "602", 1, "T-0301", "Northgate Marble Works"), // lease ended: no current tenant
    ))

    private val _meters = MutableStateFlow(seedMeters())
    override val meters: StateFlow<List<Meter>> = _meters.asStateFlow()

    private val _readings = MutableStateFlow(seedReadings(_meters.value))
    override val readings: StateFlow<List<Reading>> = _readings.asStateFlow()

    /** Demo switch: when false, readings are saved on the phone as if there were no signal. */
    override val online = MutableStateFlow(true)

    override val isDemo: Boolean = true

    /** The demo keeps photos on the phone. */
    override val photosWaiting: StateFlow<Int> = MutableStateFlow(0)

    override suspend fun signIn(login: String): SignInResult = SignInResult.Success

    override suspend fun refresh(): Boolean = online.value

    override suspend fun submit(draft: ReadingDraft): SubmitResult {
        tenantRefusal(draft)?.let { return SubmitResult(SubmitOutcome.REJECTED, it) }
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
        return SubmitResult(outcome)
    }

    override suspend fun sendQueued(): Int {
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
        SubmitOutcome.REJECTED -> ReadingState.READ_AGAIN
    }

    private fun seedMeters(): List<Meter> {
        val lastRead = LocalDate.of(2026, 9, 3)
        fun m(
            n: Int, number: String, type: MeterType, property: String, zone: String, route: Int,
            previous: Long?, state: ReadingState = ReadingState.PENDING, note: String? = null,
        ) = Meter(
            id = "BC%04d".format(n), number = number, type = type, propertyCode = property, zoneCode = zone, route = route,
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
            m(10, "2002-5", s, "1499-W1", "598", 6, null), // never read (BR-004)
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
