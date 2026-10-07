package com.meterreading.reader.util

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads screen instructions aloud for readers who find reading hard. */
class AndroidSpeaker(context: Context) : Speaker, TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)

    @Volatile
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS &&
            tts.setLanguage(Locale.UK).let { it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED }
        if (ready) tts.setSpeechRate(0.85f)
    }

    /** Returns false when the phone has no usable English voice. */
    override fun speak(text: String): Boolean {
        if (!ready) return false
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "instruction")
        return true
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
