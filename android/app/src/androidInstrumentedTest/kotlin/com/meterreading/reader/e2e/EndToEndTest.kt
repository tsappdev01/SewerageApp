package com.meterreading.reader.e2e

import android.graphics.Bitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meterreading.reader.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * End to end: the real app on an emulator, through the DMZ gateway and the API, into SQL Server with the
 * development data (db/dev). Run by .github/workflows/e2e.yml, which builds the stack, passes a fresh
 * registration code (-e regCode) and afterwards checks in the database what these tests sent
 * (db/dev/900_e2e_checks.sql). The tests share one phone, so they run in name order.
 *
 * They tap the screens as a reader would, by the texts on them (commonMain/composeResources), and save a
 * screenshot of each step under the app's files (e2e/), which the workflow keeps.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EndToEndTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val pin = "2468"

    @Test fun t1_FR002_phone_is_registered_with_a_code_and_opens_as_its_reader() {
        val code = args.getString("regCode")
        assumeTrue("no -e regCode given", !code.isNullOrBlank())
        // Not registered yet: the server refuses the reader and the start screen stays.
        waitText("Open")
        shot("01_start_not_registered")

        clickDesc("Settings")
        // First time: the supervisor chooses a PIN.
        waitText("New supervisor PIN")
        field("PIN").performTextInput(pin)
        field("Type the PIN again").performTextInput(pin)
        shot("02_new_supervisor_pin")
        click("OK")

        waitText("Registration code from IT")
        field("Registration code from IT").performTextInput(code!!)
        shot("03_settings_registration_code")
        click("Register this phone")

        // Registered: the app opens as the phone's reader (rashid@dip.example in the debug build).
        waitText("Hello, Rashid", timeoutMs = 60_000)
        shot("04_home_after_registration")
    }

    @Test fun t2_FR006_meter_is_read_with_a_photo_and_sent() {
        waitText("Hello, Rashid", timeoutMs = 60_000)
        click("Start")
        // Meter BC0003 (1101-I): last 30,110, about 1,000 a month. Tenant T-0102 (FR-006.12).
        readMeter(number = "31050", tenantCode = "T-0102", prefix = "1")
        waitAnyText(listOf("Sent", "Sent for checking"), timeoutMs = 60_000)
        shot("1z_result_sent")
        click("Home")
        waitText("Hello, Rashid")
    }

    @Test fun t3_FR020_without_signal_the_reading_waits_and_is_sent_when_signal_is_back() {
        waitText("Hello, Rashid", timeoutMs = 60_000)
        network(on = false)
        try {
            click("Start")
            // Meter BC0004 (1101-S): last 99,950.
            readMeter(number = "99990", tenantCode = "T-0102", prefix = "2")
            waitText("Saved", timeoutMs = 30_000)
            shot("2z_saved_no_signal")
            click("Home")
            waitText("1 waiting to send")
            shot("30_home_waiting")
        } finally {
            network(on = true)
        }
        // FR-020.4: the reader is asked before anything is sent.
        waitText("Signal is back", timeoutMs = 90_000)
        shot("31_signal_is_back")
        click("Send now")
        waitText("All sent", substring = true, timeoutMs = 90_000)
        shot("32_home_all_sent")
    }

    @Test fun t4_FR031_inspection_visit_with_photo_is_sent() {
        waitText("Hello, Rashid", timeoutMs = 60_000)
        click("Field Inspection")
        waitText("Inspection plan")
        click("Late")
        waitText("Elegant Industries LLC")
        shot("40_plan_late")
        click("Elegant Industries LLC")
        waitText("Start inspection")
        shot("41_property")
        click("Start inspection")
        waitText("Are you at the property now?")
        click("Yes, I am here")

        waitText("GURCOAT GARAGE L.L.C")
        shot("42_units")
        // Unit 1: swipe right, "as recorded".
        rows("GURCOAT GARAGE L.L.C")[0].performTouchInput { swipeRight() }
        waitText("As recorded")
        // Unit 2: vacant, which needs a photo (FR-033).
        rows("GURCOAT GARAGE L.L.C")[1].performClick()
        waitText("What did you find?")
        click("Vacant")
        click("Empty", substring = false, optional = true)
        click("Add photo")
        takePhoto("43")
        waitText("Save unit", timeoutMs = 30_000)
        shot("44_unit_vacant_with_photo")
        click("Save unit")

        waitText("Check and send")
        shot("45_units_after")
        click("Check and send")
        waitText("Send inspection")
        shot("46_review")
        click("Send inspection")
        waitAnyText(listOf("Sent", "Saved on the phone"), timeoutMs = 90_000)
        shot("47_inspection_sent")
        click("Back to the plan", optional = true)
    }

    @Test fun t5_my_readings_and_summary_show_the_work() {
        waitText("Hello, Rashid", timeoutMs = 60_000)
        click("My readings")
        waitText("1101-I", substring = true)
        shot("50_my_readings")
        clickDesc("Back")
        click("Summary")
        waitText("My summary")
        shot("51_summary")
        clickDesc("Back")
    }

    // ---- a reading, step by step, whatever steps this meter asks for ----

    private fun readMeter(number: String, tenantCode: String, prefix: String) {
        var step = 0
        repeat(40) {
            when {
                has("Yes, send") -> {
                    shot("${prefix}${step++}_check")
                    click(tenantCode)
                    shot("${prefix}${step++}_tenant_checked")
                    click("Yes, send")
                    return
                }
                has("How is the meter?") -> {
                    shot("${prefix}${step++}_condition")
                    click("Working")
                    click("Next: photo", optional = true) || click("Next")
                }
                hasDesc("Take photo") -> takePhoto("${prefix}${step++}")
                has("Is the photo clear?") -> {
                    shot("${prefix}${step++}_photo_check")
                    click("Good photo")
                }
                has("It is correct") -> {
                    shot("${prefix}${step++}_warning")
                    click("It is correct")
                }
                has("Type the number") -> {
                    number.forEach { d -> compose.onAllNodes(hasText(d.toString()) and hasClickAction()).onFirst().performClick() }
                    shot("${prefix}${step++}_number")
                    clickDesc("Done")
                }
                else -> Thread.sleep(500)
            }
            compose.waitForIdle()
        }
        error("The reading did not reach the check screen")
    }

    private fun takePhoto(prefix: String) {
        compose.waitUntil(20_000) { hasDesc("Take photo") }
        Thread.sleep(2_000) // the camera's first frames
        shot("${prefix}_camera")
        clickDesc("Take photo")
        compose.waitUntil(30_000) { !hasDesc("Take photo") }
        if (has("Is the photo clear?")) {
            shot("${prefix}_photo_check")
            click("Good photo")
        }
    }

    // ---- helpers ----

    private fun has(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun hasDesc(desc: String) =
        compose.onAllNodes(hasContentDescription(desc)).fetchSemanticsNodes().isNotEmpty()

    private fun waitText(text: String, substring: Boolean = false, timeoutMs: Long = 20_000) {
        try {
            compose.waitUntil(timeoutMs) { has(text, substring) }
        } catch (e: Throwable) {
            shot("FAILED_waiting_for_${text.filter { it.isLetterOrDigit() }}")
            throw AssertionError("\"$text\" did not appear in ${timeoutMs / 1000} s", e)
        }
    }

    private fun waitAnyText(texts: List<String>, timeoutMs: Long) {
        try {
            compose.waitUntil(timeoutMs) { texts.any { has(it) } }
        } catch (e: Throwable) {
            shot("FAILED_waiting_for_${texts.first().filter { it.isLetterOrDigit() }}")
            throw AssertionError("none of $texts appeared in ${timeoutMs / 1000} s", e)
        }
    }

    /** Clicks the first clickable node showing [text]; false when [optional] and it is not there. */
    private fun click(text: String, substring: Boolean = false, optional: Boolean = false): Boolean {
        val matcher = hasText(text, substring = substring)
        val clickable = compose.onAllNodes(matcher and hasClickAction()).fetchSemanticsNodes()
        if (clickable.isEmpty() && compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            if (optional) return false
            waitText(text, substring)
        }
        val node = if (compose.onAllNodes(matcher and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodes(matcher and hasClickAction()).onFirst()
        } else {
            compose.onAllNodes(matcher).onFirst()
        }
        scrollTo(node).performClick()
        compose.waitForIdle()
        return true
    }

    private fun clickDesc(desc: String) {
        compose.waitUntil(20_000) { hasDesc(desc) }
        scrollTo(compose.onAllNodes(hasContentDescription(desc)).onFirst()).performClick()
        compose.waitForIdle()
    }

    private fun field(label: String): SemanticsNodeInteraction =
        scrollTo(compose.onAllNodes(hasText(label) and hasSetTextAction()).onFirst())

    private fun rows(text: String): List<SemanticsNodeInteraction> {
        val matcher: SemanticsMatcher = hasText(text) and hasClickAction()
        val count = compose.onAllNodes(matcher).fetchSemanticsNodes().size
        return (0 until count).map { compose.onAllNodes(matcher)[it] }
    }

    private fun scrollTo(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
        runCatching { node.performScrollTo() }
        return node
    }

    /** Turns the emulator's Wi-Fi and mobile data off or on, as walking out of signal would. */
    private fun network(on: Boolean) {
        val state = if (on) "enable" else "disable"
        shell("svc wifi $state")
        shell("svc data $state")
        Thread.sleep(3_000)
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
    }

    /** The whole screen, dialogs included, to the app's files/e2e (pulled by the workflow). */
    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "e2e").apply { mkdirs() }
        File(dir, "${javaClass.simpleName}_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
