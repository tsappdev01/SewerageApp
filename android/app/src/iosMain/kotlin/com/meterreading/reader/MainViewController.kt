package com.meterreading.reader

import androidx.compose.ui.window.ComposeUIViewController
import com.meterreading.reader.platform.IosPlatform
import com.meterreading.reader.platform.IosSpeaker
import com.meterreading.reader.ui.App
import platform.UIKit.UIViewController

/** The app's only screen for iOS (iPhone and iPad): the shared Compose app. Called from ios/iosApp. */
fun MainViewController(): UIViewController {
    IosPlatform.start()
    val speaker = IosSpeaker()
    return ComposeUIViewController { App(speaker) }
}
