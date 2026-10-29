package com.meterreading.reader.data

import kotlinx.datetime.Clock
import com.meterreading.reader.platform.*
import com.meterreading.reader.api.ApiClient
import com.meterreading.reader.api.ApiException
import com.meterreading.reader.api.MeterDto
import com.meterreading.reader.api.ReadingDto
import com.meterreading.reader.api.SubmitReadingRequest
import com.meterreading.reader.api.SubmitReadingResponse
import com.meterreading.reader.api.SyncDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.meterreading.reader.platform.File
import okio.IOException
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/**
 * Meters and readings from the Meter Reading API. Readings that cannot be sent (no signal, or a
 * server error worth retrying) wait in a queue and go up with [sendQueued], keeping their
 * transaction id so a retry is never stored twice (BR-013).
 *
 * Photos go up after their reading is stored, one at a time with their SHA-256, and are deleted
 * from the phone once the server has them (FR-008.6). A photo that cannot be sent waits too; the
 * reading is not held up by its photos.
 *
 * The queue is saved, encrypted, after every change ([store], FR-020) and reloaded at start, so it
 * survives the app being closed; photos of saved readings are encrypted on disk ([vault], FR-008.6).
 * [onWaiting] is told whenever something waits, so the phone can send it in the background.
 */
class ApiMeterRepository(
    private val api: ApiClient,
    private val zone: TimeZone = localZone,
    private val store: QueueStore? = null,
    private val vault: PhotoVault = PhotoVault(null),
    private val onWaiting: (() -> Unit)? = null,
    private val listCache: MeterListCache? = null,
    private val clock: () -> Instant = { Clock.System.now() },
    /** How long [submit] waits for the server's answer before the reader moves on (DIP: within 1 second). */
    private val quickAnswerMillis: Long = 1_000,
) : MeterRepository() {
    /** Sends that outlive the screen that started them: a slow answer, and photos after their reading. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _readerName = MutableStateFlow("")
    override val readerName: StateFlow<String> = _readerName.asStateFlow()

    private val _meters = MutableStateFlow<List<Meter>>(emptyList())
    override val meters: StateFlow<List<Meter>> = _meters.asStateFlow()

    private val _readings = MutableStateFlow<List<Reading>>(emptyList())
    override val readings: StateFlow<List<Reading>> = _readings.asStateFlow()

    private val _properties = MutableStateFlow<List<Property>>(emptyList())
    override val properties: StateFlow<List<Property>> = _properties.asStateFlow()

    override val online = MutableStateFlow(true)

    private val _photosWaiting = MutableStateFlow(0)
    override val photosWaiting: StateFlow<Int> = _photosWaiting.asStateFlow()

    private val _statuses = MutableStateFlow<List<MeterStatusOption>>(emptyList())
    override val statuses: StateFlow<List<MeterStatusOption>> = _statuses.asStateFlow()

    private val _savedListFrom = MutableStateFlow<Instant?>(null)
    override val savedListFrom: StateFlow<Instant?> = _savedListFrom.asStateFlow()
    private val _periodClosed = MutableStateFlow(false)
    override val periodClosed: StateFlow<Boolean> = _periodClosed.asStateFlow()

    /** The last list from the server, kept so the saved copy can follow readings sent since. */
    private var lastSync: SyncDto? = null
    private var lastMine: List<ReadingDto> = emptyList()

    private val queue = ArrayDeque<ReadingDraft>()
    private val photoQueue = ArrayDeque<QueueStore.WaitingPhoto>()
    private val lock = Mutex()

    init {
        // What was waiting when the app last closed.
        store?.load()?.let { saved ->
            queue.addAll(saved.readings)
            photoQueue.addAll(saved.photos)
            _readings.value = queue.reversed().map { it.toQueuedReading() }
            _photosWaiting.value = photoQueue.size
            if (hasWaiting()) onWaiting?.invoke()
        }
    }

    /** Saves the queue after a change, and asks for a background send while anything waits. */
    private fun persist() {
        store?.save(QueueStore.Snapshot(queue.toList(), photoQueue.toList()))
        if (queue.isNotEmpty() || photoQueue.isNotEmpty()) onWaiting?.invoke()
    }

    override suspend fun signIn(login: String): SignInResult {
        api.devUser = login.trim().ifEmpty { null }
        return try {
            val me = api.me()
            _readerName.value = me.displayName
            if (me.openPeriod == null) {
                SignInResult.Failed("There is no open reading period. Ask your supervisor.")
            } else {
                signInNeeded.value = false
                signInReason = null
                refresh()
                SignInResult.Success
            }
        } catch (e: ApiException) {
            SignInResult.Failed(e.title)
        } catch (e: IOException) {
            online.value = false
            // FR-020.1: no signal at start. Open with the saved list if it is this reader's and recent.
            val saved = listCache?.load(api.baseUrl, login, clock())
            if (saved != null) {
                lock.withLock {
                    _readerName.value = saved.readerName
                    show(saved.sync, saved.mine)
                    _savedListFrom.value = Instant.ofEpochMilli(saved.savedAtMillis)
                }
                SignInResult.Success
            } else {
                SignInResult.Failed("Cannot reach the server. Check the signal and try again.")
            }
        }
    }

    /** Puts a list from the server (or its saved copy) on screen, keeping readings still waiting. */
    private fun show(sync: SyncDto, mine: List<ReadingDto>) {
        _periodClosed.value = sync.period.status.equals("CLOSED", ignoreCase = true)
        lastSync = sync
        lastMine = mine
        val waiting = queue.associateBy { it.meterId }
        // FR-006.2: in PMS's order (vw_MR_MeterStatus.SequenceNo), whatever order they arrive in.
        _statuses.value = sync.statuses.sortedBy { it.sequence }.mapNotNull { s ->
            MeterCondition.entries.firstOrNull { it.name == s.kind }?.let { MeterStatusOption(s.code, s.name, it) }
        }
        _properties.value = sync.properties.map {
            Property(
                it.code, it.name ?: it.code, it.zoneCode, it.routeSequence ?: Int.MAX_VALUE, it.tenantCode, it.companyName,
                it.tenants.map { t -> Tenant(t.code, t.companyName) },
            )
        }
        _meters.value = sync.meters.map { m ->
            val meter = m.toMeter()
            if (m.id in waiting) meter.copy(state = ReadingState.QUEUED) else meter
        }
        _readings.value = queue.reversed().map { it.toQueuedReading() } + mine.map { it.toReading() }
    }

    /** Keeps the phone's copy of the list up to date (FR-020.1). */
    private fun saveList() {
        val cache = listCache ?: return
        val sync = lastSync ?: return
        val login = api.devUser ?: return
        cache.save(MeterListCache.Saved(api.baseUrl, login, _readerName.value, clock().toEpochMilli(), sync, lastMine))
    }

    // NonCancellable: leaving Home (or the screen locking) must not abort a slow list download; it
    // finishes and is kept, so the next screen shows it (the server can take 30 s after a restart).
    override suspend fun refresh(): Boolean = withContext(NonCancellable) { lock.withLock {
        try {
            val sync = api.meters()
            val mine = api.myReadings()
            show(sync, mine)
            _savedListFrom.value = null
            saveList()
            online.value = true
            true
        } catch (e: IOException) {
            online.value = false
            false
        } catch (e: ApiException) {
            if (e.needsSignIn) { signInReason = e.title; signInNeeded.value = true }
            false
        }
    } }

    /**
     * The reading is kept on the phone first (queued), then sent. The reader waits at most
     * [quickAnswerMillis] for the server: an answer in that time (sent, checking, refused) is shown at
     * once; a slower one arrives in the background and the meter shows it (sent, or read again with the
     * server's reason). Photos always go up in the background after their reading is stored.
     */
    override suspend fun submit(draft: ReadingDraft): SubmitResult {
        tenantRefusal(draft)?.let { return SubmitResult(SubmitOutcome.REJECTED, it) } // never saved or queued
        draft.photos.forEach { vault.seal(it.path) }
        lock.withLock { enqueue(draft) } // nothing is lost if the answer is slow or the app is closed
        val answer = background.async {
            val result = lock.withLock { sendOne(draft) }
            if (result.outcome == SubmitOutcome.SENT || result.outcome == SubmitOutcome.CHECKING) lock.withLock { uploadPhotos() }
            result
        }
        return withTimeoutOrNull(quickAnswerMillis) { answer.await() } ?: SubmitResult(SubmitOutcome.SENDING)
    }

    /** Sends one queued reading now. Under [lock]. */
    private suspend fun sendOne(draft: ReadingDraft): SubmitResult {
        // Sent meanwhile by sendQueued (the background send): its answer is already on the meter.
        if (queue.none { it.transactionId == draft.transactionId }) return SubmitResult(SubmitOutcome.SENT)
        return try {
            val response = api.submit(draft.toRequest())
            online.value = true
            queue.removeAll { it.transactionId == draft.transactionId }
            applyStored(draft, response) // saves the queue
            SubmitResult(if (response.status == "EXCEPTION") SubmitOutcome.CHECKING else SubmitOutcome.SENT)
        } catch (e: IOException) {
            online.value = false
            SubmitResult(SubmitOutcome.QUEUED)
        } catch (e: ApiException) {
            if (e.isRetryable || e.needsSignIn) {
                // Not accepted as this reader: keep the reading on the phone; it goes up after signing in again.
                if (e.needsSignIn) { signInReason = e.title; signInNeeded.value = true }
                SubmitResult(SubmitOutcome.QUEUED)
            } else {
                // Not stored: the reader reads the meter again (new photos), with the server's reason on it.
                queue.removeAll { it.transactionId == draft.transactionId }
                persist()
                deletePhotos(draft)
                _readings.update { list -> list.filterNot { it.transactionId == draft.transactionId } }
                updateMeter(draft.meterId) { it.copy(state = ReadingState.READ_AGAIN, supervisorNote = e.title) }
                SubmitResult(SubmitOutcome.REJECTED, e.title)
            }
        }
    }

    /** Waits for sends still going in the background (tests). */
    internal suspend fun awaitBackground() {
        background.coroutineContext.job.children.toList().joinAll()
    }

    override suspend fun finalizeProperty(propertyCode: String): FinalizeResult = try {
        val result = api.finalizeProperty(propertyCode)
        online.value = true
        FinalizeResult.Done(result.rows.size)
    } catch (e: IOException) {
        online.value = false
        FinalizeResult.NoSignal
    } catch (e: ApiException) {
        if (e.needsSignIn) { signInReason = e.title; signInNeeded.value = true }
        FinalizeResult.Refused(e.title)
    }

    override suspend fun closeMonth(): CloseMonthResult {
        // Everything on the phone goes up first: a month closed with readings still here would refuse them.
        if (hasWaiting()) sendQueued()
        if (hasWaiting()) return if (online.value) CloseMonthResult.StillWaiting(waitingItems()) else CloseMonthResult.NoSignal
        return try {
            val result = api.closePeriod()
            online.value = true
            _periodClosed.value = true
            refresh()
            CloseMonthResult.Done(result.periodCode, result.alreadyClosed)
        } catch (e: IOException) {
            online.value = false
            CloseMonthResult.NoSignal
        } catch (e: ApiException) {
            if (e.needsSignIn) { signInReason = e.title; signInNeeded.value = true }
            CloseMonthResult.Refused(e.title)
        }
    }

    override suspend fun sendQueued(): Int {
        var sent = 0
        lock.withLock {
            while (queue.isNotEmpty()) {
                val draft = queue.first()
                try {
                    val response = api.submit(draft.toRequest())
                    queue.removeFirst()
                    applyStored(draft, response) // saves the queue
                    sent++
                } catch (e: IOException) {
                    online.value = false
                    break
                } catch (e: ApiException) {
                    if (e.needsSignIn) { signInReason = e.title; signInNeeded.value = true }
                    if (e.isRetryable || e.needsSignIn) break
                    // Final refusal (e.g. already read by someone else): show it as "read again" with the reason.
                    queue.removeFirst()
                    persist()
                    deletePhotos(draft)
                    _readings.update { list -> list.filterNot { it.transactionId == draft.transactionId } }
                    updateMeter(draft.meterId) { it.copy(state = ReadingState.READ_AGAIN, supervisorNote = e.title) }
                }
            }
            if (queue.isEmpty()) sent += uploadPhotos()
            if (sent > 0) online.value = true
        }
        if (sent > 0) refresh()
        return sent
    }

    /** Uploads waiting photos in order; stops at the first one that cannot be sent now. Returns how many went up. */
    private suspend fun uploadPhotos(): Int {
        var uploaded = 0
        while (photoQueue.isNotEmpty()) {
            val pending = photoQueue.first()
            val file = File(pending.photo.path)
            if (!file.exists()) {
                photoQueue.removeFirst()
                persist()
                continue
            }
            val bytes = vault.read(file.path)
            try {
                api.uploadPhoto(
                    transactionId = pending.transactionId,
                    imageId = pending.photo.imageId,
                    role = pending.photo.role.name,
                    capturedAtUtc = pending.capturedAt.atZoneInstant(zone).toString(),
                    bytes = bytes,
                    sha256Hex = sha256Hex(bytes),
                )
                photoQueue.removeFirst()
                file.delete()
                persist()
                uploaded++
            } catch (e: IOException) {
                online.value = false
                break
            } catch (e: ApiException) {
                // A photo damaged on the way is sent again later; any other refusal is final, the file is kept.
                if (e.needsSignIn) { signInReason = e.title; signInNeeded.value = true }
                if (e.isRetryable || e.needsSignIn || e.code == "IMAGE_HASH_MISMATCH") break
                photoQueue.removeFirst()
                persist()
            }
        }
        _photosWaiting.value = photoQueue.size
        return uploaded
    }

    private fun deletePhotos(draft: ReadingDraft) {
        draft.photos.forEach { File(it.path).delete() }
    }

    private fun enqueue(draft: ReadingDraft) {
        if (queue.none { it.transactionId == draft.transactionId }) queue.addLast(draft)
        persist()
        _readings.update { list -> listOf(draft.toQueuedReading()) + list.filterNot { it.transactionId == draft.transactionId } }
        updateMeter(draft.meterId) { it.copy(state = ReadingState.QUEUED) }
    }

    private fun applyStored(draft: ReadingDraft, response: SubmitReadingResponse) {
        val state = response.state.toReadingState()
        _readings.update { list ->
            listOf(
                Reading(
                    transactionId = response.transactionId,
                    meterId = response.meterId,
                    condition = draft.condition,
                    value = draft.value,
                    capturedAt = draft.capturedAt,
                    state = if (state == ReadingState.REVISIT) ReadingState.SENT else state,
                    needsCheck = response.status == "EXCEPTION",
                ),
            ) + list.filterNot { it.transactionId == response.transactionId }
        }
        updateMeter(draft.meterId) { it.copy(state = state, supervisorNote = null) }
        // The saved list follows, so a meter sent just now is not offered again after a restart without signal.
        lastSync = lastSync?.let { s -> s.copy(meters = s.meters.map { if (it.id == draft.meterId) it.copy(state = response.state) else it }) }
        saveList()
        draft.photos.forEach { photo ->
            if (photoQueue.none { it.photo.imageId == photo.imageId }) photoQueue.addLast(QueueStore.WaitingPhoto(response.transactionId, photo, draft.capturedAt))
        }
        persist()
        _photosWaiting.value = photoQueue.size
    }

    private fun updateMeter(id: String, change: (Meter) -> Meter) {
        _meters.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun ReadingDraft.toRequest() = SubmitReadingRequest(
        transactionId = transactionId,
        meterId = meterId,
        condition = condition.name,
        reasonCode = reasonCode,
        note = note.ifBlank { null },
        newReading = numbers[NumberTarget.CURRENT],
        oldFinalReading = numbers[NumberTarget.OLD_FINAL],
        newMeterNumber = newMeterNumber,
        newOpeningReading = numbers[NumberTarget.NEW_OPENING],
        newCurrentReading = numbers[NumberTarget.NEW_CURRENT],
        readerConfirmedWarning = readerConfirmedWarning,
        capturedAtUtc = capturedAt.atZoneInstant(zone).toString(),
        photoCount = photos.size,
        subTenant = subTenant?.trim()?.ifEmpty { null },
        tenantCode = tenantCode,
        statusCode = statusCode,
    )

    private fun ReadingDraft.toQueuedReading() = Reading(transactionId, meterId, condition, value, capturedAt, ReadingState.QUEUED, needsCheck = false)

    private fun MeterDto.toMeter() = Meter(
        id = id,
        number = number,
        type = if (type == "IRRIGATION") MeterType.IRRIGATION else MeterType.SEWERAGE,
        propertyCode = propertyCode,
        zoneCode = zoneCode,
        route = routeSequence ?: Int.MAX_VALUE,
        registerDigits = registerDigits,
        previousReading = previousReading?.toLong(),
        previousDate = null,
        expectedHigh = expectedHigh?.toLong(),
        state = state.toReadingState(),
        supervisorNote = supervisorNote,
        lastConsumption = lastConsumption?.toLong(),
        isFirstReading = isFirstReading,
    )

    private fun ReadingDto.toReading() = Reading(
        transactionId = transactionId,
        meterId = meterId,
        condition = runCatching { MeterCondition.valueOf(condition) }.getOrDefault(MeterCondition.WORKING),
        value = newReading?.toLong(),
        capturedAt = LocalDateTime.ofInstant(Instant.parse(capturedAtUtc), zone),
        state = state.toReadingState().let { if (it == ReadingState.REVISIT) ReadingState.SENT else it },
        needsCheck = status == "EXCEPTION",
    )


    private fun String.toReadingState(): ReadingState =
        runCatching { ReadingState.valueOf(this) }.getOrDefault(ReadingState.PENDING)
}
