package com.meterreading.reader.data

import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The supervisor PIN that protects Settings. Only a salted, slow hash is kept on the phone. After
 * [MAX_TRIES] wrong PINs, entry is blocked for [LOCK_SECONDS], so a 4-digit PIN cannot be guessed
 * by trying them all. A forgotten PIN is reset by clearing the app's data (MDM or phone settings).
 */
object SupervisorPin {
    const val MAX_TRIES = 5
    const val LOCK_SECONDS = 60L
    private const val ITERATIONS = 20_000

    /** Stored PIN: salt and hash, both Base64. */
    data class Stored(val salt: String, val hash: String)

    /** Wrong tries so far and when entry opens again (epoch millis, 0 = open). */
    data class Attempts(val failures: Int = 0, val blockedUntilMillis: Long = 0)

    sealed interface Result {
        data class Ok(val attempts: Attempts) : Result
        data class Wrong(val triesLeft: Int, val attempts: Attempts) : Result
        data class Blocked(val secondsLeft: Long, val attempts: Attempts) : Result
    }

    /** 4 to 8 digits. */
    fun isValid(pin: String): Boolean = pin.length in 4..8 && pin.all { it in '0'..'9' }

    fun create(pin: String, random: SecureRandom = SecureRandom()): Stored {
        require(isValid(pin)) { "PIN must be 4 to 8 digits" }
        val salt = ByteArray(16).also(random::nextBytes)
        return Stored(Base64.getEncoder().encodeToString(salt), hash(pin, salt))
    }

    fun check(pin: String, stored: Stored, attempts: Attempts, nowMillis: Long): Result {
        if (nowMillis < attempts.blockedUntilMillis) {
            return Result.Blocked((attempts.blockedUntilMillis - nowMillis + 999) / 1000, attempts)
        }
        val matches = MessageDigest.isEqual(
            hash(pin, Base64.getDecoder().decode(stored.salt)).toByteArray(),
            stored.hash.toByteArray(),
        )
        if (matches) return Result.Ok(Attempts())
        val failures = attempts.failures + 1
        return if (failures >= MAX_TRIES) {
            val next = Attempts(0, nowMillis + LOCK_SECONDS * 1000)
            Result.Blocked(LOCK_SECONDS, next)
        } else {
            Result.Wrong(MAX_TRIES - failures, Attempts(failures, 0))
        }
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(bytes)
    }
}
