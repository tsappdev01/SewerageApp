package com.meterreading.reader.e2e

import android.content.Intent
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern

/**
 * The phone as a person uses it: finds what is on the screen by its text or its spoken label
 * (contentDescription) and taps, types and swipes, through Android's accessibility layer (the one
 * TalkBack uses). The app runs exactly as on a reader's phone; nothing inside it is replaced.
 */
class Phone(private val testName: String) {
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots = File(context.getExternalFilesDir(null), "e2e").apply { mkdirs() }

    /** Opens the app from the launcher, as a new start. */
    fun openApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 20_000)
    }

    /**
     * [text] as one whole label on the screen. Compose may give a button's texts to the button as one
     * label ("Start, Next: 597 · …"), so a whole item of such a list counts too.
     */
    fun item(text: String): BySelector = By.text(Pattern.compile("(?s)(.*, )?${Pattern.quote(text)}(, .*)?"))
    fun containing(text: String): BySelector = By.text(Pattern.compile("(?s).*${Pattern.quote(text)}.*"))
    fun desc(text: String): BySelector = By.desc(Pattern.compile("(?s)(.*, )?${Pattern.quote(text)}(, .*)?"))

    fun has(selector: BySelector) = device.hasObject(selector)
    fun has(text: String) = has(item(text))

    fun waitFor(selector: BySelector, what: String, timeoutMs: Long = 20_000): UiObject2 {
        val found = device.wait(Until.findObject(selector), timeoutMs)
        if (found == null) {
            fail("\"$what\" did not appear in ${timeoutMs / 1000} s")
        }
        return found!!
    }

    fun waitText(text: String, timeoutMs: Long = 20_000) = waitFor(item(text), text, timeoutMs)
    fun waitContaining(text: String, timeoutMs: Long = 20_000) = waitFor(containing(text), text, timeoutMs)

    fun waitAny(texts: List<String>, timeoutMs: Long): String {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            texts.firstOrNull { has(it) }?.let { return it }
            Thread.sleep(500)
        }
        fail("none of $texts appeared in ${timeoutMs / 1000} s")
        error("unreachable")
    }

    /** Taps [text], scrolling down to it when it is below the screen. False when [optional] and absent. */
    fun tap(text: String, optional: Boolean = false, timeoutMs: Long = 20_000): Boolean {
        val node = find(item(text), if (optional) 1_500 else timeoutMs) ?: run {
            if (optional) return false
            fail("\"$text\" is not on the screen to tap")
            return false
        }
        node.click()
        settle()
        return true
    }

    fun tapDesc(label: String, timeoutMs: Long = 20_000) {
        val node = find(desc(label), timeoutMs) ?: fail("no button \"$label\" to tap")
        node!!.click()
        settle()
    }

    /**
     * Types into the text field labelled [label] (the label sits inside the field). If the label is not
     * its own item on the screen, the [orIndex]-th text field from the top is used.
     */
    fun type(label: String, value: String, orIndex: Int) {
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 20_000)
        val fields = device.findObjects(By.clazz("android.widget.EditText")).sortedBy { it.visibleBounds.top }
        val at = device.findObject(item(label))?.visibleBounds
        val field = at?.let { b -> fields.firstOrNull { Rect.intersects(it.visibleBounds, b) } }
            ?: fields.firstOrNull { it.text?.contains(label) == true || it.contentDescription?.contains(label) == true }
            ?: fields.getOrNull(orIndex)
            ?: fail("no field \"$label\"")
        field!!.click()
        field.text = value
        settle()
    }

    fun swipeRight(node: UiObject2) {
        node.swipe(Direction.RIGHT, 0.9f)
        settle()
    }

    fun all(text: String): List<UiObject2> = device.findObjects(item(text)).sortedBy { it.visibleBounds.top }

    /** Wi-Fi and mobile data off or on, as walking out of signal would. */
    fun network(on: Boolean) {
        val state = if (on) "enable" else "disable"
        device.executeShellCommand("svc wifi $state")
        device.executeShellCommand("svc data $state")
        Thread.sleep(3_000)
    }

    /** The whole screen, dialogs included, to the app's files/e2e (pulled by the workflow). */
    fun shot(name: String) {
        settle()
        device.takeScreenshot(File(shots, "${testName}_$name.png"))
    }

    fun fail(message: String): UiObject2? {
        val tag = message.filter { it.isLetterOrDigit() }.take(40)
        runCatching { device.takeScreenshot(File(shots, "${testName}_FAILED_$tag.png")) }
        runCatching { device.dumpWindowHierarchy(File(shots, "${testName}_FAILED_$tag.xml")) }
        throw AssertionError(message)
    }

    private fun settle() {
        device.waitForIdle(3_000)
        Thread.sleep(400)
    }

    /** Finds [selector], scrolling the screen's scrollable area down (then up) when it is not visible. */
    private fun find(selector: BySelector, timeoutMs: Long): UiObject2? {
        device.wait(Until.findObject(selector), timeoutMs)?.let { return it }
        val scrollable = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.area() } ?: return null
        repeat(6) {
            device.findObject(selector)?.let { return it }
            if (!scrollable.scroll(Direction.DOWN, 0.7f)) return@repeat
        }
        repeat(8) {
            device.findObject(selector)?.let { return it }
            if (!scrollable.scroll(Direction.UP, 0.7f)) return@repeat
        }
        return device.findObject(selector)
    }

    private fun Rect.area() = width() * height()
}
