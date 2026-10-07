package com.meterreading.reader.platform

import com.meterreading.reader.util.Speaker
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance

/** Reads screen instructions aloud with the iPhone's or iPad's English voice. */
class IosSpeaker : Speaker {
    private val synthesizer = AVSpeechSynthesizer()
    private val voice = AVSpeechSynthesisVoice.voiceWithLanguage("en-GB") ?: AVSpeechSynthesisVoice.voiceWithLanguage("en-US")

    override fun speak(text: String): Boolean {
        val chosen = voice ?: return false
        if (synthesizer.speaking) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val utterance = AVSpeechUtterance(string = text).apply {
            voice = chosen
            rate = 0.45f
        }
        synthesizer.speakUtterance(utterance)
        return true
    }
}
