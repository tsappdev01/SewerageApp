package com.meterreading.reader.util

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Reads screen instructions aloud for readers who find reading hard: Android's text-to-speech
 * (androidMain) or iOS's AVSpeechSynthesizer (iosMain).
 */
interface Speaker {
    /** Returns false when the phone has no usable English voice. */
    fun speak(text: String): Boolean
}

val LocalSpeaker = staticCompositionLocalOf<Speaker?> { null }
