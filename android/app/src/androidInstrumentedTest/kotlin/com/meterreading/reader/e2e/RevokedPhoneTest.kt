package com.meterreading.reader.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-002.3: after IT blocks the phone (db/ops/revoke_device.sql, run by the e2e workflow after
 * [EndToEndTest]) the app no longer opens: the server refuses it and the start screen stays.
 */
@RunWith(AndroidJUnit4::class)
class RevokedPhoneTest {
    private val phone = Phone("RevokedPhoneTest")

    @Test fun FR002_3_a_blocked_phone_cannot_open_the_app() {
        phone.openApp()
        phone.waitText("Open", 30_000)
        Thread.sleep(15_000) // the refused sign-in comes back
        phone.shot("60_blocked_phone")
        assertFalse("A blocked phone must not reach Home", phone.has("Hello, Rashid"))
    }
}
