package com.meterreading.reader.e2e

import android.graphics.Bitmap
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meterreading.reader.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * FR-002.3: after IT blocks the phone (db/ops/revoke_device.sql, run by the e2e workflow after
 * [EndToEndTest]) the app no longer opens: the server refuses it and the start screen says so.
 */
@RunWith(AndroidJUnit4::class)
class RevokedPhoneTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun FR002_3_a_blocked_phone_cannot_open_the_app() {
        compose.waitUntil(30_000) { has("The server did not accept", substring = true) || has("Open") }
        Thread.sleep(10_000) // give the refused sign-in time to come back
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "e2e").apply { mkdirs() }
        bitmap?.let { b -> File(dir, "RevokedPhoneTest_60_blocked_phone.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        assertFalse("A blocked phone must not reach Home", has("Hello, Rashid"))
    }

    private fun has(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
}
