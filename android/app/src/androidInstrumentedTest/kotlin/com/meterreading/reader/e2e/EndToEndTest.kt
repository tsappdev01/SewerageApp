package com.meterreading.reader.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

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
    private val phone = Phone("EndToEndTest")
    private val pin = "2468"

    @Test fun t1_FR002_phone_is_registered_with_a_code_and_opens_as_its_reader() {
        val code = InstrumentationRegistry.getArguments().getString("regCode")
        assumeTrue("no -e regCode given", !code.isNullOrBlank())
        phone.openApp()
        // Not registered yet: the server refuses the reader and the start screen stays.
        phone.waitText("Open", 30_000)
        Thread.sleep(3_000)
        phone.shot("01_start_not_registered")

        phone.tapDesc("Settings")
        // First time: the supervisor chooses a PIN.
        phone.waitText("New supervisor PIN")
        phone.type("PIN", pin, orIndex = 0)
        phone.type("Type the PIN again", pin, orIndex = 1)
        phone.shot("02_new_supervisor_pin")
        phone.tap("OK")

        phone.waitText("Registration code from IT")
        phone.type("Registration code from IT", code!!, orIndex = 1)
        phone.shot("03_settings_registration_code")
        phone.tap("Register this phone")

        // Registered: the app opens as the phone's reader (rashid@dip.example in the debug build).
        phone.waitText("Hello, Rashid", 60_000)
        phone.shot("04_home_after_registration")
    }

    @Test fun t2_FR006_meter_is_read_with_a_photo_and_sent() {
        home()
        phone.tap("Start")
        // Meter BC0003 (1101-I): last 30,110, about 1,000 a month. Tenant T-0102 (FR-006.12).
        readMeter(number = "31050", tenantCode = "T-0102", prefix = "1")
        phone.waitAny(listOf("Sent", "Sent for checking"), 60_000)
        phone.shot("1z_result_sent")
        phone.tap("Home")
        phone.waitText("Hello, Rashid")
    }

    @Test fun t3_FR020_without_signal_the_reading_waits_and_is_sent_when_signal_is_back() {
        home()
        phone.network(on = false)
        try {
            phone.tap("Start")
            // Meter BC0004 (1101-S): last 99,950.
            readMeter(number = "99990", tenantCode = "T-0102", prefix = "2")
            phone.waitText("Saved", 30_000)
            phone.shot("2z_saved_no_signal")
            phone.tap("Home")
            phone.waitContaining("waiting to send")
            phone.shot("30_home_waiting")
        } finally {
            phone.network(on = true)
        }
        // FR-020.4: the reader is asked before anything is sent.
        phone.waitText("Signal is back", 90_000)
        phone.shot("31_signal_is_back")
        phone.tap("Send now")
        phone.waitContaining("All sent", 90_000)
        phone.shot("32_home_all_sent")
    }

    @Test fun t4_FR031_inspection_visit_with_photo_is_sent() {
        home()
        phone.tap("Field Inspection")
        phone.waitText("Inspection plan")
        phone.tap("Late")
        phone.waitText("Elegant Industries LLC")
        phone.shot("40_plan_late")
        phone.tap("Elegant Industries LLC")
        phone.waitText("Start inspection")
        phone.shot("41_property")
        phone.tap("Start inspection")
        phone.waitText("Are you at the property now?")
        phone.tap("Yes, I am here")

        phone.waitText("GURCOAT GARAGE L.L.C", 30_000)
        phone.shot("42_units")
        // Unit 1: swipe right, "as recorded".
        phone.swipeRight(phone.all("GURCOAT GARAGE L.L.C")[0])
        phone.waitText("As recorded")
        // Unit 2: vacant, which needs a photo (FR-033).
        phone.all("GURCOAT GARAGE L.L.C")[1].click()
        phone.waitText("What did you find?")
        phone.tap("Vacant")
        phone.tap("Empty", optional = true)
        phone.tap("Add photo")
        takePhoto("43")
        phone.waitText("Photos: 1", 30_000)
        phone.shot("44_unit_vacant_with_photo")
        phone.tap("Save unit") // below the photos: scrolls to it

        phone.waitText("Check and send")
        phone.shot("45_units_after")
        phone.tap("Check and send")
        phone.waitText("Send inspection")
        phone.shot("46_review")
        phone.tap("Send inspection")
        phone.waitAny(listOf("Sent", "Saved on the phone"), 90_000)
        phone.shot("47_inspection_sent")
    }

    @Test fun t5_my_readings_and_summary_show_the_work() {
        home()
        phone.tap("My readings")
        phone.waitContaining("1101-I")
        phone.shot("50_my_readings")
        phone.device.pressBack()
        phone.waitText("Hello, Rashid")
        phone.tap("Summary")
        phone.waitText("My summary")
        phone.shot("51_summary")
    }

    private fun home() {
        phone.openApp()
        phone.waitText("Hello, Rashid", 60_000)
    }

    // ---- a reading, step by step, whatever steps this meter asks for ----

    private fun readMeter(number: String, tenantCode: String, prefix: String) {
        var step = 0
        repeat(40) {
            when {
                phone.has("Yes, send") -> {
                    phone.shot("${prefix}${step++}_check")
                    phone.tap(tenantCode)
                    phone.shot("${prefix}${step++}_tenant_checked")
                    phone.tap("Yes, send")
                    return
                }
                phone.has("How is the meter?") -> {
                    phone.shot("${prefix}${step++}_condition")
                    phone.tap("Working")
                    phone.tap("Next: photo", optional = true) || phone.tap("Next")
                }
                phone.has(phone.desc("Take photo")) -> takePhoto("${prefix}${step++}")
                phone.has("Is the photo clear?") -> {
                    phone.shot("${prefix}${step++}_photo_check")
                    phone.tap("Good photo")
                }
                phone.has("It is correct") -> {
                    phone.shot("${prefix}${step++}_warning")
                    phone.tap("It is correct")
                }
                phone.has("Type the number") -> {
                    number.forEach { d ->
                        val key = phone.device.findObjects(phone.item(d.toString()).clickable(true)).firstOrNull()
                            ?: phone.device.findObjects(phone.item(d.toString())).maxByOrNull { it.visibleBounds.top }
                            ?: phone.fail("no key $d")
                        key!!.click()
                        Thread.sleep(200)
                    }
                    phone.shot("${prefix}${step++}_number")
                    phone.tapDesc("Done")
                }
                else -> Thread.sleep(700)
            }
        }
        phone.fail("The reading did not reach the check screen")
    }

    private fun takePhoto(prefix: String) {
        phone.waitFor(phone.desc("Take photo"), "Take photo")
        Thread.sleep(2_500) // the camera's first frames
        phone.shot("${prefix}_camera")
        phone.tapDesc("Take photo")
        phone.device.wait(androidx.test.uiautomator.Until.gone(phone.desc("Take photo")), 30_000)
        if (phone.device.wait(androidx.test.uiautomator.Until.hasObject(phone.item("Is the photo clear?")), 3_000) == true) {
            phone.shot("${prefix}_photo_check")
            phone.tap("Good photo")
        }
    }

}
