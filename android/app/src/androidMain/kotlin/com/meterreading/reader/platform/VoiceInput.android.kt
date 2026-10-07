package com.meterreading.reader.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState

/**
 * Starts the phone's speech-to-text in English. Returns a function that launches it and
 * reports false when the phone has no speech service (the caller then offers typing).
 */
@Composable
actual fun rememberVoiceInput(prompt: String, onText: (String) -> Unit): () -> Boolean {
    val callback = rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { callback.value(it) }
        }
    }
    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        try {
            launcher.launch(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
