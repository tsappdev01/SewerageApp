package com.meterreading.reader.data

import kotlin.concurrent.Volatile
import com.meterreading.reader.platform.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant

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

    /**
     * When the meter list on screen came from the phone's saved copy (no signal at start), the time
     * the server sent it; null while the list is fresh from the server (FR-020.1).
     */
    open val savedListFrom: StateFlow<Instant?> = MutableStateFlow(null)

    /** Photos of stored readings still to upload. */
    abstract val photosWaiting: StateFlow<Int>

    /**
     * Set when the server did not accept the reader (HTTP 401).
     * Readings and photos stay on the phone; the app goes back to the sign-in screen.
     */
    val signInNeeded = MutableStateFlow(false)

    /** The server's words when it refused (e.g. "This phone has been blocked"), for the start screen. */
    @Volatile
    var signInReason: String? = null

    /** Sample data, not a server: the sign-in screen shows the "no signal" demo switch. */
    open val isDemo: Boolean = false

    abstract suspend fun signIn(login: String): SignInResult

    /** Reloads meters and readings from the server. Returns false when there is no connection. */
    abstract suspend fun refresh(): Boolean

    abstract suspend fun submit(draft: ReadingDraft): SubmitResult

    /** FR-006.12: why a draft cannot be saved because of its tenant, or null when it can. */
    protected fun tenantRefusal(draft: ReadingDraft): String? =
        TenantRules.problem(property(meter(draft.meterId).propertyCode), draft.tenantCode)?.message

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

/**
 * Single place the screens get the repository from. MainActivity sets it at start; saving the
 * Settings screen replaces it (new server address or sign-in), and [current] lets screens follow.
 */
object AppGraph {
    private val _current = MutableStateFlow<MeterRepository?>(null)
    val current: StateFlow<MeterRepository?> = _current

    var repository: MeterRepository
        get() = _current.value ?: error("AppGraph.repository is not set yet")
        set(value) { _current.value = value }

    val isReady: Boolean get() = _current.value != null

    private val _inspections = MutableStateFlow<InspectionRepository?>(null)
    /** Field Inspection (spec §21), set next to [repository]. */
    val inspectionsFlow: StateFlow<InspectionRepository?> = _inspections
    var inspections: InspectionRepository?
        get() = _inspections.value
        set(value) { _inspections.value = value }

    /** Readings, inspections or photos are waiting on the phone. */
    fun hasWaiting(): Boolean = repository.hasWaiting() || inspections?.hasWaiting() == true

    /** Sends everything waiting: readings first, then inspections. Returns how many items went up. */
    suspend fun sendAll(): Int = repository.sendQueued() + (inspections?.sendQueued() ?: 0)
}
