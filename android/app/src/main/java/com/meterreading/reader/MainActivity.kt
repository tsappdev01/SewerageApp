package com.meterreading.reader

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.meterreading.reader.ui.nav.AppNavHost
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.MeterReaderTheme
import com.meterreading.reader.util.LocalSpeaker
import com.meterreading.reader.util.Speaker

class MainActivity : ComponentActivity() {
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light system bars always: the app is light-only for outdoor use.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        speaker = Speaker(this)
        setContent {
            MeterReaderTheme {
                CompositionLocalProvider(LocalSpeaker provides speaker) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(AppColors.Background)
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        AppNavHost()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }
}
