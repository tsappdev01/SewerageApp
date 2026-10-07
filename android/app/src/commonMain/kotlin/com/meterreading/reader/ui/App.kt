package com.meterreading.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meterreading.reader.platform.Messages
import com.meterreading.reader.ui.nav.AppNavHost
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.MeterReaderTheme
import com.meterreading.reader.util.LocalSpeaker
import com.meterreading.reader.util.Speaker

/** The whole app, the same on Android (MainActivity) and iOS (MainViewController). */
@Composable
fun App(speaker: Speaker?) {
    MeterReaderTheme {
        CompositionLocalProvider(LocalSpeaker provides speaker) {
            val messages = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { Messages.shown.collect { messages.showSnackbar(it, duration = SnackbarDuration.Long) } }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(AppColors.Background)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                // Tablets (iPad, Android tablets): the phone layout in a centred column of readable width,
                // rather than cards and buttons stretched across the whole screen.
                Box(Modifier.fillMaxHeight().widthIn(max = MAX_CONTENT_WIDTH).align(Alignment.TopCenter)) {
                    AppNavHost()
                }
                SnackbarHost(messages, Modifier.align(Alignment.BottomCenter).widthIn(max = MAX_CONTENT_WIDTH).padding(16.dp))
            }
        }
    }
}

/** Widest the screens are drawn: a large phone's width and a little more. */
private val MAX_CONTENT_WIDTH = 720.dp
