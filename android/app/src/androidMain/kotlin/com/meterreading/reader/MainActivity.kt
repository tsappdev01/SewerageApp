package com.meterreading.reader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.meterreading.reader.platform.AndroidPlatform
import com.meterreading.reader.settings.AppServices
import com.meterreading.reader.settings.SyncNotification
import com.meterreading.reader.ui.App
import com.meterreading.reader.util.AndroidSpeaker

/** A FragmentActivity because Android's lock prompt (BiometricPrompt) needs one. */
class MainActivity : FragmentActivity() {
    private lateinit var speaker: AndroidSpeaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light system bars always: the app is light-only for outdoor use.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        speaker = AndroidSpeaker(this)
        // Settings saved on the phone pick the server and the sign-in; done once for the app's life.
        AndroidPlatform.start(applicationContext)
        askForNotificationsOnce()
        setContent {
            App(speaker)
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
