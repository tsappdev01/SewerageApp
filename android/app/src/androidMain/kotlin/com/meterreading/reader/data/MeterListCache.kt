package com.meterreading.reader.data

import com.meterreading.reader.api.ReadingDto
import com.meterreading.reader.api.SyncDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * The last meter list from the server (meters, properties, tenants, zones, "my readings"), kept
 * encrypted on the phone (FR-020.1) so the app can open and take readings without signal.
 * A copy is only used for the same reader and server, and while it is younger than [MAX_AGE]:
 * the server does not accept readings older than seven days anyway (BR spec §7.2, MaxQueueAgeDays).
 */
class MeterListCache(private val file: File, private val sealer: Sealer?) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable
    data class Saved(
        val serverUrl: String,
        val readerLogin: String,
        val readerName: String,
        /** When the server sent this list (epoch milliseconds). */
        val savedAtMillis: Long,
        val sync: SyncDto,
        val mine: List<ReadingDto>,
    )

    fun save(saved: Saved) {
        val plain = json.encodeToString(Saved.serializer(), saved).toByteArray(Charsets.UTF_8)
        writeAtomically(file, sealer?.seal(plain) ?: plain)
    }

    /** The saved list for this reader and server, or null if there is none, it is someone else's, or too old. */
    fun load(serverUrl: String, readerLogin: String, now: Instant): Saved? {
        if (!file.exists()) return null
        val saved = try {
            val bytes = file.readBytes()
            val plain = sealer?.let { if (it.isSealed(bytes)) it.open(bytes) else bytes } ?: bytes
            json.decodeFromString(Saved.serializer(), plain.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            file.delete() // only a copy of the server's data: safe to drop
            return null
        }
        val sameReader = saved.serverUrl == serverUrl && saved.readerLogin.equals(readerLogin.trim(), ignoreCase = true)
        val fresh = Duration.between(Instant.ofEpochMilli(saved.savedAtMillis), now) < MAX_AGE
        return if (sameReader && fresh) saved else null
    }

    companion object {
        val MAX_AGE: Duration = Duration.ofDays(7)
    }
}
