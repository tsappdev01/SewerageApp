package com.meterreading.reader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.meterreading.reader.settings.SyncNotification
import android.os.Bundle
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
import androidx.fragment.app.FragmentActivity
import com.meterreading.reader.settings.AppServices
import com.meterreading.reader.ui.nav.AppNavHost
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.MeterReaderTheme
import com.meterreading.reader.util.LocalSpeaker
import com.meterreading.reader.util.Speaker

/** A FragmentActivity because Android's lock prompt (BiometricPrompt) needs one. */
class MainActivity : FragmentActivity() {
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light system bars always: the app is light-only for outdoor use.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        speaker = Speaker(this)
        // Settings saved on the phone pick the server and the sign-in; done once for the app's life.
        AppServices.init(applicationContext)
        askForNotificationsOnce()
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

    // FR-001.5: after 15 minutes away the phone's lock is asked for again.
    override fun onStart() {
        super.onStart()
        AppServices.inForeground = true
        AppServices.onForeground(System.currentTimeMillis())
        SyncNotification.cancel(this) // the app asks itself while it is open
    }

    override fun onStop() {
        AppServices.inForeground = false
        AppServices.onBackground(System.currentTimeMillis())
        super.onStop()
    }

    // FR-020.4: the "Signal is back" notification needs this permission on Android 13+. Asked once.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }
}
