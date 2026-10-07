package com.meterreading.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

// Screen pieces each platform draws or handles its own way (androidMain and iosMain).

/** The system back gesture or button; iOS has none, so there it does nothing. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** SEC-007: no screenshots or app-switcher previews while this is on screen (where the platform allows). */
@Composable
expect fun SecureScreen()

/**
 * Full-screen in-app camera (FR-008.1/.2: no gallery). [showFrame] draws the white box the reader puts
 * the meter's numbers in. The photo is saved, shrunk for upload, in the app's private storage.
 */
@Composable
expect fun CameraCapture(hint: String, speakText: String, showFrame: Boolean, onCaptured: (File) -> Unit)

/**
 * Starts the phone's speech-to-text in English. The returned function starts it and returns false
 * when the phone has none (the caller then offers typing).
 */
@Composable
expect fun rememberVoiceInput(prompt: String, onText: (String) -> Unit): () -> Boolean

/**
 * FR-031.2: one location reading, asking for permission first if needed. [onResult] gets null when
 * location is off or not allowed; the visit is then saved without it.
 */
@Composable
expect fun rememberLocationRequest(onResult: (Fix?) -> Unit): () -> Unit

/** FR-001.1: the phone's own lock (PIN, pattern, finger or face). */
@Composable
expect fun rememberDeviceUnlock(): DeviceUnlock

interface DeviceUnlock {
    /** The phone has a screen lock that can be asked for. */
    fun isSetUp(): Boolean
    suspend fun unlock(title: String, subtitle: String): UnlockResult
    /** Opens the phone's own settings, to set a screen lock. */
    fun openSecuritySettings()
}

sealed interface UnlockResult {
    data object Unlocked : UnlockResult
    data object Cancelled : UnlockResult
    /** The phone has no screen lock at all: it must get one before the app can open. */
    data object NoLockSet : UnlockResult
    data class Failed(val message: String) : UnlockResult
}

/** Where the phone is, kept with an inspection visit as proof it took place (spec FR-031.2). */
data class Fix(val latitude: Double, val longitude: Double, val accuracyM: Double)

/** A captured photo for display, scaled down to [maxSize] and turned upright; null if unreadable. */
expect suspend fun loadPhoto(file: File, maxSize: Int): ImageBitmap?

/**
 * FR-008.5: makes the photo ready to upload, in place: upright, long edge at most [maxEdge] px, JPEG at
 * the highest quality that fits [maxBytes]. Call off the main thread.
 */
expect fun shrinkForUpload(file: File, maxEdge: Int = 1600, maxBytes: Int = 500_000)

/**
 * FR-033.2: writes [lines] (property, unit, time, place, inspector) on a dark band at the bottom of the
 * photo, so the evidence carries them outside the system. Call off the main thread, after [shrinkForUpload].
 */
expect fun stampPhoto(file: File, lines: List<String>)

/** [image] as JPEG bytes (the inspection signature). */
expect fun encodeJpeg(image: ImageBitmap, quality: Int): ByteArray
