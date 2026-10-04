package com.meterreading.reader.data

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts data kept on the phone (spec §9, FR-008.6). */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
    fun isSealed(bytes: ByteArray): Boolean
}

/**
 * AES-256-GCM: "MRQ1" + 12-byte IV + ciphertext with its tag. GCM also detects any change to the
 * data. On the phone the key lives in the Android Keystore and never leaves it.
 */
class AesGcmSealer(private val key: () -> SecretKey) : Sealer {
    private val random = SecureRandom()

    override fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
        return MAGIC + iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(isSealed(sealed)) { "Not sealed data" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, MAGIC.size, IV_BYTES))
        }
        val start = MAGIC.size + IV_BYTES
        return cipher.doFinal(sealed, start, sealed.size - start)
    }

    override fun isSealed(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size + IV_BYTES && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private companion object {
        val MAGIC = "MRQ1".toByteArray(Charsets.US_ASCII)
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
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
