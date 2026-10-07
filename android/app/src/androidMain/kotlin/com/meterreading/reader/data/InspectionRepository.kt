package com.meterreading.reader.data

import com.meterreading.reader.api.ApiException
import com.meterreading.reader.api.InspectionImageResponse
import com.meterreading.reader.api.InspectionPlanDto
import com.meterreading.reader.api.InspectionPlanListDto
import com.meterreading.reader.api.InspectionUnitDto
import com.meterreading.reader.api.InspectionUnitsDto
import com.meterreading.reader.api.LastUnitResultDto
import com.meterreading.reader.api.SubmitInspectionRequest
import com.meterreading.reader.api.SubmitInspectionResponse
import com.meterreading.reader.api.UnitResultRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** What the inspection screens need from the server; [com.meterreading.reader.api.ApiClient] is the real one. */
interface InspectionApi {
    val baseUrl: String
    val devUser: String?
    suspend fun inspectionPlan(): InspectionPlanListDto
    suspend fun inspectionUnits(periodCode: String, propertyCode: String, tenantCode: String): InspectionUnitsDto
    suspend fun submitInspection(request: SubmitInspectionRequest): SubmitInspectionResponse
    suspend fun uploadInspectionPhoto(
        visitId: String, imageId: String, resultId: String?, capturedAtUtc: String, bytes: ByteArray, sha256Hex: String,
    ): InspectionImageResponse
}

enum class FinishOutcome { SENT, QUEUED, REJECTED }

data class FinishResult(val outcome: FinishOutcome, val message: String? = null, val state: InspectionState? = null)

/**
 * Field Inspection on the phone (spec §21). Like meter readings: a visit is made on the phone with its own
 * id, kept encrypted from the first tap ([store]), and sent once finished; without signal it waits and is
 * sent with the readings ("Send now / Later"). The visit id is reused on retry, so it is never stored twice.
 * Photos go up after their visit, one at a time with their SHA-256, and are deleted once the server has them.
 */
class InspectionRepository(
    private val api: InspectionApi,
    private val store: InspectionStore? = null,
    private val vault: PhotoVault = PhotoVault(null),
    private val onWaiting: (() -> Unit)? = null,
    private val onSignInNeeded: ((String) -> Unit)? = null,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.of("Asia/Dubai"),
) {
    /** Null until the server answers once; false when the server has no field inspection. */
    private val _available = MutableStateFlow<Boolean?>(null)
    val available: StateFlow<Boolean?> = _available.asStateFlow()

    private val _plans = MutableStateFlow<List<InspectionPlanDto>>(emptyList())
    val plans: StateFlow<List<InspectionPlanDto>> = _plans.asStateFlow()

    private val _today = MutableStateFlow(LocalDate.now(zone))
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    /** When the plan on screen is the phone's saved copy (no signal), the time the server sent it. */
    private val _savedFrom = MutableStateFlow<Instant?>(null)
    val savedFrom: StateFlow<Instant?> = _savedFrom.asStateFlow()

    private val _drafts = MutableStateFlow<Map<String, VisitDraft>>(emptyMap())
    val drafts: StateFlow<Map<String, VisitDraft>> = _drafts.asStateFlow()

    private val _waitingVisits = MutableStateFlow(0)
    val waitingVisits: StateFlow<Int> = _waitingVisits.asStateFlow()

    private val _waitingPhotos = MutableStateFlow(0)
    val waitingPhotos: StateFlow<Int> = _waitingPhotos.asStateFlow()

    private var saved = store?.load() ?: InspectionStore.Snapshot()
    private val units = saved.units.toMutableMap()
    private val queue = ArrayDeque(saved.queue)
    private val photoQueue = ArrayDeque(saved.photos)
    private val lock = Mutex()

    init {
        _drafts.value = saved.drafts
        counts()
        if (hasWaiting()) onWaiting?.invoke()
        // Without signal at start the saved plan is shown at once (FR-036).
        savedPlan()?.let { showPlan(it, fromCopy = true) }
    }

    fun hasWaiting(): Boolean = queue.isNotEmpty() || photoQueue.isNotEmpty()

    /** FR-031.2: how far the location taken for [draft] is from the DIP office, when both are known. */
    fun officeDistanceKm(draft: VisitDraft): Double? {
        val plan = saved.plan ?: return null
        val oLat = plan.officeLatitude ?: return null
        val oLon = plan.officeLongitude ?: return null
        val lat = draft.latitude ?: return null
        val lon = draft.longitude ?: return null
        if (draft.atProperty == false) return null
        return InspectionRules.distanceKm(oLat, oLon, lat, lon)
    }

    private fun counts() {
        _waitingVisits.value = queue.size
        _waitingPhotos.value = photoQueue.size
    }

    private fun persist() {
        saved = saved.copy(units = units.toMap(), drafts = _drafts.value, queue = queue.toList(), photos = photoQueue.toList())
        store?.save(saved)
        counts()
        if (hasWaiting()) onWaiting?.invoke()
    }

    /** The saved plan if it is this reader's, from this server, and not older than a week. */
    private fun savedPlan(): InspectionPlanListDto? {
        val plan = saved.plan ?: return null
        val same = saved.serverUrl == api.baseUrl && saved.readerLogin.equals(api.devUser.orEmpty(), ignoreCase = true)
        val fresh = Duration.between(Instant.ofEpochMilli(saved.planSavedAtMillis), clock()) < MAX_AGE
        return if (same && fresh) plan else null
    }

    private fun showPlan(list: InspectionPlanListDto, fromCopy: Boolean) {
        _today.value = runCatching { LocalDate.parse(list.today) }.getOrDefault(LocalDate.now(zone))
        // Visits still waiting on the phone count already.
        val waiting = queue.associateBy { it.planId }
        _plans.value = list.plans.map { p -> waiting[p.id]?.let { localProgress(p, it) } ?: p }
        _savedFrom.value = if (fromCopy) Instant.ofEpochMilli(saved.planSavedAtMillis) else null
        if (fromCopy) _available.value = true
    }

    /** Loads the plan. False without signal (the saved plan stays on screen) or when the server refuses. */
    suspend fun refreshPlan(): Boolean = lock.withLock {
        try {
            val list = api.inspectionPlan()
            _available.value = true
            saved = saved.copy(serverUrl = api.baseUrl, readerLogin = api.devUser.orEmpty(), planSavedAtMillis = clock().toEpochMilli(), plan = list)
            showPlan(list, fromCopy = false)
            persist()
            true
        } catch (e: IOException) {
            savedPlan()?.let { showPlan(it, fromCopy = true) }
            false
        } catch (e: ApiException) {
            if (e.code == "INSPECTION_OFF") _available.value = false
            if (e.needsSignIn) onSignInNeeded?.invoke(e.title)
            false
        }
    }

    fun plan(planId: String): InspectionPlanDto? = _plans.value.firstOrNull { it.id == planId }

    /** The units of a plan row, fresh from the server, else the phone's copy. Null when neither has them. */
    suspend fun units(plan: InspectionPlanDto): InspectionUnitsDto? {
        try {
            val fresh = api.inspectionUnits(plan.periodCode, plan.propertyCode, plan.tenantCode)
            lock.withLock {
                units[plan.id] = fresh
                persist()
            }
            return fresh
        } catch (e: IOException) {
            return lock.withLock { units[plan.id] }
        } catch (e: ApiException) {
            if (e.needsSignIn) onSignInNeeded?.invoke(e.title)
            return lock.withLock { units[plan.id] }
        }
    }

    fun cachedUnits(planId: String): InspectionUnitsDto? = units[planId]

    fun draft(planId: String): VisitDraft? = _drafts.value[planId]

    /** Starts a visit (or returns the one in progress). Its id is made now and kept until it is stored. */
    fun startVisit(plan: InspectionPlanDto): VisitDraft {
        _drafts.value[plan.id]?.let { return it }
        val draft = VisitDraft(
            visitId = UUID.randomUUID().toString(),
            periodCode = plan.periodCode,
            propertyCode = plan.propertyCode,
            tenantCode = plan.tenantCode,
            companyName = plan.companyName,
            startedAtUtc = clock().toString(),
        )
        _drafts.value = _drafts.value + (plan.id to draft)
        persist()
        return draft
    }

    /** Changes a visit in progress and saves it at once, so nothing is lost if the app closes. */
    fun update(planId: String, change: (VisitDraft) -> VisitDraft) {
        val current = _drafts.value[planId] ?: return // only startVisit creates a visit
        _drafts.value = _drafts.value + (planId to change(current))
        persist()
    }

    fun saveEntry(planId: String, entry: UnitEntry) = update(planId) { it.copy(entries = it.entries + (entry.key to entry)) }

    fun removeEntry(planId: String, key: String) {
        val entry = _drafts.value[planId]?.entries?.get(key) ?: return
        entry.photos.forEach { File(it.path).delete() }
        update(planId) { it.copy(entries = it.entries - key) }
    }

    /** Drops a visit in progress and its photos. */
    fun discard(planId: String) {
        val draft = _drafts.value[planId] ?: return
        draft.entries.values.flatMap { it.photos }.forEach { File(it.path).delete() }
        draft.signature?.let { File(it.path).delete() }
        _drafts.value = _drafts.value - planId
        persist()
    }

    /** Why a visit cannot be sent yet: nothing recorded, or the first unit with something missing. */
    fun finishProblem(draft: VisitDraft): Pair<UnitEntry?, InspectionRules.Problem>? {
        if (draft.recorded.isEmpty()) return null to InspectionRules.Problem.NO_RESULT
        return draft.recorded.firstNotNullOfOrNull { e -> InspectionRules.problem(e)?.let { e to it } }
    }

    /**
     * Sends a finished visit (FR-036). Without signal it is kept to send later. A refusal by the server
     * keeps the visit open on the phone with the server's reason, so no work is lost.
     */
    suspend fun finish(planId: String): FinishResult = lock.withLock {
        val open = _drafts.value[planId] ?: return@withLock FinishResult(FinishOutcome.REJECTED, "This inspection was not found on the phone.")
        if (finishProblem(open) != null) return@withLock FinishResult(FinishOutcome.REJECTED, "Some units are not complete.")
        val draft = open.copy(finishedAtUtc = open.finishedAtUtc ?: clock().toString())
        draft.recorded.flatMap { it.photos }.forEach { vault.seal(it.path) }
        draft.signature?.let { vault.seal(it.path) }
        _drafts.value = _drafts.value - planId
        // From here the visit is only in this call: it must end stored or queued, even if the screen
        // that asked for it goes away and cancels its scope (FR-036).
        withContext(NonCancellable) { sendOrKeep(planId, open, draft) }
    }

    private suspend fun sendOrKeep(planId: String, open: VisitDraft, draft: VisitDraft): FinishResult {
        return try {
            val response = api.submitInspection(draft.toRequest())
            stored(draft, InspectionState.valueOf(response.state))
            uploadPhotos()
            FinishResult(FinishOutcome.SENT, state = InspectionState.valueOf(response.state))
        } catch (e: IOException) {
            enqueue(draft)
            FinishResult(FinishOutcome.QUEUED, state = plan(planId)?.state?.let(InspectionState::valueOf))
        } catch (e: ApiException) {
            if (e.isRetryable || e.needsSignIn) {
                if (e.needsSignIn) onSignInNeeded?.invoke(e.title)
                enqueue(draft)
                FinishResult(FinishOutcome.QUEUED)
            } else {
                // Not stored: the visit stays open with everything recorded, for the inspector to correct.
                _drafts.value = _drafts.value + (planId to open)
                persist()
                FinishResult(FinishOutcome.REJECTED, e.title)
            }
        }
    }

    /** Sends visits and photos kept on the phone. Returns how many items went up. */
    suspend fun sendQueued(): Int = lock.withLock {
        var sent = 0
        while (queue.isNotEmpty()) {
            val draft = queue.first()
            try {
                val response = api.submitInspection(draft.toRequest())
                queue.removeFirst()
                stored(draft, InspectionState.valueOf(response.state))
                sent++
            } catch (e: IOException) {
                break
            } catch (e: ApiException) {
                if (e.needsSignIn) onSignInNeeded?.invoke(e.title)
                if (e.isRetryable || e.needsSignIn) break
                // Refused for good (e.g. the plan changed): reopen it on the phone so the work can be corrected.
                queue.removeFirst()
                _drafts.value = _drafts.value + (draft.planId to draft.copy(finishedAtUtc = null))
                persist()
            }
        }
        if (queue.isEmpty()) sent += uploadPhotos()
        sent
    }

    private fun enqueue(draft: VisitDraft) {
        if (queue.none { it.visitId == draft.visitId }) queue.addLast(draft)
        plan(draft.planId)?.let { p -> replacePlan(localProgress(p, draft)) }
        markUnits(draft)
        persist()
    }

    /** The server has the visit: its photos are next, and the plan shows the new state. */
    private fun stored(draft: VisitDraft, state: InspectionState) {
        draft.recorded.forEach { e ->
            e.photos.forEach { photo ->
                if (photoQueue.none { it.photo.imageId == photo.imageId }) photoQueue.addLast(InspectionStore.WaitingPhoto(draft.visitId, e.resultId, photo))
            }
        }
        draft.signature?.let { s ->
            if (photoQueue.none { it.photo.imageId == s.imageId }) photoQueue.addLast(InspectionStore.WaitingPhoto(draft.visitId, null, s))
        }
        plan(draft.planId)?.let { p -> replacePlan(localProgress(p, draft).copy(state = state.name)) }
        markUnits(draft)
        persist()
    }

    /** Units of the plan row remember what was found, for the next visit and "last inspection". */
    private fun markUnits(draft: VisitDraft) {
        val list = units[draft.planId] ?: return
        val now = clock().toString()
        units[draft.planId] = list.copy(
            units = list.units.map { u ->
                draft.entries[u.unitId]?.result?.let { r -> u.copy(last = LastUnitResultDto(r.name, now, draft.entries[u.unitId]?.occupantName, draft.entries[u.unitId]?.peopleSeen)) } ?: u
            },
        )
    }

    private fun replacePlan(p: InspectionPlanDto) {
        _plans.value = _plans.value.map { if (it.id == p.id) p else it }
        units[p.id]?.let { units[p.id] = it.copy(plan = p) }
    }

    /** The plan row as it will be once [draft] is stored, worked out on the phone. */
    private fun localProgress(p: InspectionPlanDto, draft: VisitDraft): InspectionPlanDto {
        val list = units[p.id]?.units ?: return p.copy(state = InspectionState.COME_BACK.name)
        val results = list.associate { it.unitId to InspectionRules.currentResult(it, draft, p) }
        return p.copy(
            state = InspectionRules.stateAfter(list, draft, p).name,
            checkedUnits = results.values.count { it != null && it != UnitResult.PENDING },
            flaggedUnits = results.values.count { it in InspectionRules.flagged },
            lastVisitAtUtc = draft.finishedAtUtc,
            distanceFromOfficeKm = officeDistanceKm(draft)?.let { Math.round(it * 10) / 10.0 } ?: p.distanceFromOfficeKm,
        )
    }

    /** Uploads waiting photos in order; stops at the first one that cannot go now. */
    private suspend fun uploadPhotos(): Int {
        var uploaded = 0
        while (photoQueue.isNotEmpty()) {
            val waiting = photoQueue.first()
            val file = File(waiting.photo.path)
            if (!file.exists()) {
                photoQueue.removeFirst()
                persist()
                continue
            }
            val bytes = vault.read(file.path)
            try {
                api.uploadInspectionPhoto(waiting.visitId, waiting.photo.imageId, waiting.resultId, waiting.photo.capturedAtUtc, bytes, sha256Hex(bytes))
                photoQueue.removeFirst()
                file.delete()
                persist()
                uploaded++
            } catch (e: IOException) {
                break
            } catch (e: ApiException) {
                if (e.needsSignIn) onSignInNeeded?.invoke(e.title)
                if (e.isRetryable || e.needsSignIn || e.code == "IMAGE_HASH_MISMATCH") break
                photoQueue.removeFirst() // refused for good; the file stays on the phone
                persist()
            }
        }
        counts()
        return uploaded
    }

    private fun VisitDraft.toRequest() = SubmitInspectionRequest(
        visitId = visitId,
        periodCode = periodCode,
        propertyCode = propertyCode,
        tenantCode = tenantCode,
        startedAtUtc = startedAtUtc,
        finishedAtUtc = finishedAtUtc ?: startedAtUtc,
        units = recorded.map { e ->
            UnitResultRequest(
                resultId = e.resultId,
                unitId = e.unitId,
                result = e.result!!.name,
                peopleSeen = e.peopleSeen,
                occupantName = e.occupantName.trim().ifEmpty { null },
                reasons = e.reasons.ifEmpty { null },
                note = e.note.trim().ifEmpty { null },
                photoCount = e.photos.size,
                unitCode = if (e.isNew) e.unitCode.trim() else null,
                buildingName = if (e.isNew) e.buildingName?.trim()?.ifEmpty { null } else null,
                category = if (e.isNew) e.category else null,
            )
        },
        personMet = personMet.trim().ifEmpty { null },
        hasSignature = signature != null,
        // Away from the property no location is sent, even if one was taken earlier.
        latitude = latitude.takeIf { atProperty != false },
        longitude = longitude.takeIf { atProperty != false },
        gpsAccuracyM = gpsAccuracyM.takeIf { atProperty != false },
        atProperty = atProperty,
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    companion object {
        /** The server takes visits up to seven days old; an older plan copy is not shown. */
        val MAX_AGE: Duration = Duration.ofDays(7)

        /** A new unit entry for a unit on the list. */
        fun entryFor(unit: InspectionUnitDto): UnitEntry =
            UnitEntry(UUID.randomUUID().toString(), unit.unitId, unit.unitCode, unit.buildingName, unit.category)

        /** A unit found on site that is not on the list (FR-035). */
        fun newUnitEntry(): UnitEntry = UnitEntry(UUID.randomUUID().toString(), null, "")
    }
}
