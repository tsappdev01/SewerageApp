package com.meterreading.reader.data

import com.meterreading.reader.platform.*
import com.meterreading.reader.platform.File

/**
 * Encrypts data kept on the phone (spec §9, FR-008.6), with a key the platform holds: the Android
 * Keystore ([AesGcmSealer], androidMain) or the iOS Keychain (iosMain).
 */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
    fun isSealed(bytes: ByteArray): Boolean
}

/**
 * Photo files of readings waiting on the phone, encrypted in place once the reading is saved, and
 * opened only to upload. Without a sealer (tests, demo) files stay as they are.
 */
class PhotoVault(private val sealer: Sealer?) {
    fun seal(path: String) {
        val s = sealer ?: return
        val file = File(path)
        if (!file.exists()) return
        val bytes = file.readBytes()
        if (s.isSealed(bytes)) return
        writeAtomically(file, s.seal(bytes))
    }

    fun read(path: String): ByteArray {
        val bytes = File(path).readBytes()
        val s = sealer ?: return bytes
        return if (s.isSealed(bytes)) s.open(bytes) else bytes
    }
}

/** Writes to a temporary file first, so a crash never leaves half a file. */
internal fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temp = File(file.path + ".tmp")
    temp.writeBytes(bytes)
    if (!temp.renameTo(file)) {
        file.delete()
        check(temp.renameTo(file)) { "Could not write ${file.name}" }
    }
}
