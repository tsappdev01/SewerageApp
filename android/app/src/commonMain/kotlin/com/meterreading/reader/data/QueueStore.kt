package com.meterreading.reader.data

import com.meterreading.reader.platform.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.meterreading.reader.platform.File
import kotlinx.datetime.LocalDateTime

/**
 * Readings and photos waiting on the phone (spec §9, FR-020), kept in one encrypted file so they
 * survive the app being closed or the phone restarting. Written after every change.
 */
class QueueStore(private val file: File, private val sealer: Sealer?) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Snapshot(val readings: List<ReadingDraft>, val photos: List<WaitingPhoto>)

    /** A photo of a stored reading still to upload. */
    data class WaitingPhoto(val transactionId: String, val photo: DraftPhoto, val capturedAt: LocalDateTime)

    /** The saved queue, or an empty one. A file that cannot be opened is kept aside, never lost silently. */
    fun load(): Snapshot {
        if (!file.exists()) return Snapshot(emptyList(), emptyList())
        return try {
            val bytes = file.readBytes()
            val plain = sealer?.let { if (it.isSealed(bytes)) it.open(bytes) else bytes } ?: bytes
            json.decodeFromString(Stored.serializer(), plain.decodeToString()).toSnapshot()
        } catch (e: Exception) {
            file.renameTo(File(file.path + ".unreadable-" + currentTimeMillis()))
            Snapshot(emptyList(), emptyList())
        }
    }

    fun save(snapshot: Snapshot) {
        val plain = json.encodeToString(Stored.serializer(), Stored.of(snapshot)).encodeToByteArray()
        writeAtomically(file, sealer?.seal(plain) ?: plain)
    }

    @Serializable
    private data class Stored(val version: Int = 1, val readings: List<StoredDraft> = emptyList(), val photos: List<StoredWaitingPhoto> = emptyList()) {
        fun toSnapshot() = Snapshot(readings.map { it.toDraft() }, photos.map { it.toWaiting() })

        companion object {
            fun of(s: Snapshot) = Stored(readings = s.readings.map(StoredDraft::of), photos = s.photos.map(StoredWaitingPhoto::of))
        }
    }

    @Serializable
    private data class StoredPhoto(val imageId: String, val role: String, val path: String) {
        fun toPhoto() = DraftPhoto(imageId, ImageRole.valueOf(role), path)

        companion object {
            fun of(p: DraftPhoto) = StoredPhoto(p.imageId, p.role.name, p.path)
        }
    }

    @Serializable
    private data class StoredDraft(
        val transactionId: String,
        val meterId: String,
        val condition: String,
        val reasonCode: String? = null,
        val note: String = "",
        val numbers: Map<String, Long> = emptyMap(),
        val newMeterNumber: String? = null,
        val photos: List<StoredPhoto> = emptyList(),
        val readerConfirmedWarning: Boolean = false,
        val capturedAt: String,
        val subTenant: String? = null,
        val tenantCode: String? = null,
    ) {
        fun toDraft() = ReadingDraft(
            transactionId = transactionId,
            meterId = meterId,
            condition = MeterCondition.valueOf(condition),
            reasonCode = reasonCode,
            note = note,
            numbers = numbers.mapKeys { NumberTarget.valueOf(it.key) },
            newMeterNumber = newMeterNumber,
            photos = photos.map { it.toPhoto() },
            readerConfirmedWarning = readerConfirmedWarning,
            capturedAt = LocalDateTime.parse(capturedAt),
            subTenant = subTenant,
            tenantCode = tenantCode,
        )

        companion object {
            fun of(d: ReadingDraft) = StoredDraft(
                d.transactionId, d.meterId, d.condition.name, d.reasonCode, d.note, d.numbers.mapKeys { it.key.name },
                d.newMeterNumber, d.photos.map(StoredPhoto::of), d.readerConfirmedWarning, d.capturedAt.toString(), d.subTenant, d.tenantCode,
            )
        }
    }

    @Serializable
    private data class StoredWaitingPhoto(val transactionId: String, val photo: StoredPhoto, val capturedAt: String) {
        fun toWaiting() = WaitingPhoto(transactionId, photo.toPhoto(), LocalDateTime.parse(capturedAt))

        companion object {
            fun of(w: WaitingPhoto) = StoredWaitingPhoto(w.transactionId, StoredPhoto.of(w.photo), w.capturedAt.toString())
        }
    }
}
