package com.meterreading.reader.platform

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

private val random = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

actual fun pbkdf2HmacSha256(password: String, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, keyBits))
        .encoded

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
            writeTimeout(30, TimeUnit.SECONDS)
        }
    }
}

actual fun playResultTone(sent: Boolean) {
    runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(if (sent) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, 250)
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 400)
    }
}
