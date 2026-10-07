package com.meterreading.reader.data

import com.meterreading.reader.platform.*
import com.meterreading.reader.api.InspectionPlanListDto
import com.meterreading.reader.api.InspectionUnitsDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.meterreading.reader.platform.File

/**
 * Field Inspection kept on the phone, in one encrypted file (spec §9, FR-036): the plan and units last
 * seen (so inspections work without signal), visits in progress (so a half-done inspection survives the
 * app being closed), and finished visits and photos waiting to send. Written after every change.
 */
class InspectionStore(private val file: File, private val sealer: Sealer?) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** A photo of a stored (or queued) visit still to upload; [resultId] null is the signature. */
    @Serializable
    data class WaitingPhoto(val visitId: String, val resultId: String?, val photo: EvidencePhoto)

    @Serializable
    data class Snapshot(
        val version: Int = 1,
        /** Whose plan this is: it is shown only to the same reader on the same server. */
        val serverUrl: String = "",
        val readerLogin: String = "",
        val planSavedAtMillis: Long = 0,
        val plan: InspectionPlanListDto? = null,
        val units: Map<String, InspectionUnitsDto> = emptyMap(),
        val drafts: Map<String, VisitDraft> = emptyMap(),
        val queue: List<VisitDraft> = emptyList(),
        val photos: List<WaitingPhoto> = emptyList(),
    )

    /** The saved state, or an empty one. A file that cannot be opened is kept aside, never lost silently. */
    fun load(): Snapshot {
        if (!file.exists()) return Snapshot()
        return try {
            val bytes = file.readBytes()
            val plain = sealer?.let { if (it.isSealed(bytes)) it.open(bytes) else bytes } ?: bytes
            json.decodeFromString(Snapshot.serializer(), plain.decodeToString())
        } catch (e: Exception) {
            file.renameTo(File(file.path + ".unreadable-" + currentTimeMillis()))
            Snapshot()
        }
    }

    fun save(snapshot: Snapshot) {
        val plain = json.encodeToString(Snapshot.serializer(), snapshot).encodeToByteArray()
        writeAtomically(file, sealer?.seal(plain) ?: plain)
    }
}
