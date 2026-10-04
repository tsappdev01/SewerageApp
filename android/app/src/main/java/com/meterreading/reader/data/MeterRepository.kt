package com.meterreading.reader.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the screens get their data. [ApiMeterRepository] talks to the Meter Reading API;
 * [FakeMeterRepository] runs on sample data for demos and UI work. The list helpers are shared.
 */
abstract class MeterRepository {
    abstract val readerName: StateFlow<String>
    abstract val meters: StateFlow<List<Meter>>
    abstract val readings: StateFlow<List<Reading>>
    protected abstract val properties: StateFlow<List<Property>>

    /** False after a failed call: the phone is saving readings to send later. */
    abstract val online: MutableStateFlow<Boolean>

    /** Photos of stored readings still to upload. */
    abstract val photosWaiting: StateFlow<Int>

    /** True when the sign-in screen should ask for a development sign-in name instead of company sign-in. */
    open val needsDevLogin: Boolean = false

    /** Sample data, not a server: the sign-in screen shows the "no signal" demo switch. */
    open val isDemo: Boolean = false

    abstract suspend fun signIn(login: String): SignInResult

    /** Reloads meters and readings from the server. Returns false when there is no connection. */
    abstract suspend fun refresh(): Boolean

    abstract suspend fun submit(draft: ReadingDraft): SubmitResult

    /** Sends readings and photos saved on the phone. Returns how many items were sent. */
    abstract suspend fun sendQueued(): Int

    /** Something is waiting on the phone: a reading or a photo. */
    fun hasWaiting(): Boolean = photosWaiting.value > 0 || readings.value.any { it.state == ReadingState.QUEUED }

    fun meter(id: String): Meter = meters.value.first { it.id == id }

    fun property(code: String): Property =
        properties.value.firstOrNull { it.code == code } ?: Property(code, code, "", Int.MAX_VALUE)

    fun zoneProgress(meters: List<Meter>): List<ZoneProgress> =
        meters.groupBy { it.zoneCode }
            .map { (code, list) -> ZoneProgress(code, list.size, list.count { !it.state.canCapture }) }
            .sortedWith(compareBy<ZoneProgress>({ it.done == it.total }, { it.code }))

    fun propertiesIn(zoneCode: String, meters: List<Meter>): List<PropertyProgress> =
        allProperties(meters).filter { it.property.zoneCode == zoneCode }.sortedBy { it.done == it.meters.size }

    /** Every property that has meters, in route order, for search. */
    fun allProperties(meters: List<Meter>): List<PropertyProgress> {
        val byProperty = meters.groupBy { it.propertyCode }
        return properties.value
            .filter { it.code in byProperty }
            .sortedWith(compareBy<Property>({ it.zoneCode }, { it.route }, { it.code }))
            .map { p -> PropertyProgress(p, byProperty.getValue(p.code).sortedBy { it.route }) }
    }

    fun metersAt(propertyCode: String, meters: List<Meter>): List<Meter> =
        meters.filter { it.propertyCode == propertyCode }.sortedBy { it.route }

    /** Next meter in route order; stays in the same building when it can, revisits last. */
    fun nextMeter(meters: List<Meter>, after: String? = null): Meter? {
        val routes = properties.value.associate { it.code to it.route }
        val afterProperty = after?.let { id -> meters.firstOrNull { it.id == id }?.propertyCode }
        val candidates = meters
            .filter { it.state.canCapture && it.id != after }
            .sortedWith(
                compareBy<Meter>(
                    { it.state == ReadingState.REVISIT },
                    { it.zoneCode },
                    { routes[it.propertyCode] ?: Int.MAX_VALUE },
                    { it.route },
                ),
            )
        return candidates.firstOrNull { it.propertyCode == afterProperty && it.state != ReadingState.REVISIT }
            ?: candidates.firstOrNull()
    }
}

/** Single place the screens get the repository from; MainActivity picks fake or API at start. */
object AppGraph {
    lateinit var repository: MeterRepository

    val isReady: Boolean get() = ::repository.isInitialized
}
