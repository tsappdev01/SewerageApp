@file:OptIn(ExperimentalForeignApi::class)

package com.meterreading.reader.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
    check(status == 0) { "No secure random bytes ($status)" }
    return bytes
}

actual fun pbkdf2HmacSha256(password: String, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
    val pass = password.encodeToByteArray()
    val out = ByteArray(keyBits / 8)
    val status = salt.usePinned { s ->
        out.usePinned { o ->
            CCKeyDerivationPBKDF(
                kCCPBKDF2,
                password,
                pass.size.convert(),
                s.addressOf(0).reinterpret(),
                salt.size.convert(),
                kCCPRFHmacAlgSHA256,
                iterations.convert(),
                o.addressOf(0).reinterpret(),
                out.size.convert(),
            )
        }
    }
    check(status == 0) { "PBKDF2 failed ($status)" }
    return out
}

actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
    engine {
        configureRequest {
            setTimeoutInterval(30.0)
        }
    }
}

// iOS system sounds: 1001 ("mail sent") when sent, 1057 (short tock) otherwise.
actual fun playResultTone(sent: Boolean) {
    AudioServicesPlaySystemSound(if (sent) 1001u else 1057u)
}
