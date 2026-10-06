package com.meterreading.reader.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.meterreading.reader.data.AppGraph
import com.meterreading.reader.data.FakeMeterRepository
import com.meterreading.reader.data.ImageRole
import com.meterreading.reader.data.MeterCondition
import com.meterreading.reader.data.NumberTarget
import com.meterreading.reader.ui.capture.CaptureScreen
import com.meterreading.reader.ui.capture.CaptureViewModel
import com.meterreading.reader.ui.components.SyncPromptDialog
import com.meterreading.reader.ui.screens.HomeScreen
import com.meterreading.reader.ui.screens.MetersScreen
import com.meterreading.reader.ui.screens.MyReadingsScreen
import com.meterreading.reader.ui.screens.PropertiesScreen
import com.meterreading.reader.ui.screens.SummaryScreen
import com.meterreading.reader.ui.screens.ZonesScreen
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.MeterReaderTheme
import com.meterreading.reader.util.LocalSpeaker
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Duration

/**
 * Pictures of the meter-reading screens with the demo data (FakeMeterRepository), for guides and
 * reviews. Not a test of behaviour: run only with -Pscreenshots (workflow "Android screenshots").
 * Pictures go to app/build/screenshots/.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MeterReadingScreenshots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var repo: FakeMeterRepository

    @Before fun setUp() {
        repo = FakeMeterRepository()
        AppGraph.repository = repo
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            MeterReaderTheme {
                CompositionLocalProvider(LocalSpeaker provides null) {
                    Box(Modifier.fillMaxSize().background(AppColors.Background)) { content() }
                }
            }
        }
    }

    private fun shot(name: String) {
        ShadowLooper.idleMainLooper(Duration.ofSeconds(2))
        compose.waitForIdle()
        captureScreenRoboImage("build/screenshots/$name.png")
    }

    /** The same CaptureViewModel that CaptureScreen picks up with viewModel(key = "capture-$id"). */
    private fun captureVm(meterId: String): CaptureViewModel =
        ViewModelProvider(compose.activity, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CaptureViewModel(AppGraph.repository, meterId) as T
        })["capture-$meterId", CaptureViewModel::class.java]

    /** A stand-in meter photo: dark dial with white wheels, drawn here (the demo has no camera). */
    private fun samplePhoto(digits: String): File {
        val bmp = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(58, 64, 70))
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 30, 34) }
        c.drawCircle(600f, 450f, 380f, body)
        val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 228, 222) }
        c.drawCircle(600f, 450f, 330f, face)
        val wheels = Paint().apply { color = Color.rgb(20, 20, 20) }
        c.drawRect(330f, 380f, 870f, 500f, wheels)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 92f; typeface = Typeface.MONOSPACE; letterSpacing = 0.25f; textAlign = Paint.Align.CENTER
        }
        c.drawText(digits, 600f, 475f, text)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 60, 60); textSize = 38f; textAlign = Paint.Align.CENTER }
        c.drawText("m³", 600f, 580f, label)
        val f = File(compose.activity.cacheDir, "sample-$digits.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f
    }

    @Test fun s01_home() {
        show { HomeScreen(onStart = {}, onFind = {}, onZones = {}, onReadings = {}, onSummary = {}, onSettings = {}) }
        shot("01_home")
    }

    @Test fun s02_zones() {
        show { ZonesScreen(onZone = {}, onBack = {}) }
        shot("02_zones")
    }

    @Test fun s03_properties() {
        show { PropertiesScreen(zoneCode = "598", onProperty = {}, onFind = { _, _ -> }, onBack = {}) }
        shot("03_properties_zone_598")
    }

    @Test fun s04_meters() {
        show { MetersScreen(propertyCode = "1499-W1", onMeter = {}, onBack = {}) }
        shot("04_meters_property_1499-W1")
    }

    @Test fun s05_condition() {
        show { CaptureScreen("BC0003", onNextMeter = {}, onHome = {}, onExit = {}) }
        shot("05_capture_condition")
    }

    @Test fun s06_photo_check() {
        val vm = captureVm("BC0003")
        vm.chooseCondition(MeterCondition.WORKING)
        vm.photos[ImageRole.DISPLAY] = samplePhoto("30587")
        vm.next()
        show { CaptureScreen("BC0003", onNextMeter = {}, onHome = {}, onExit = {}) }
        shot("06_capture_photo_clear")
    }

    @Test fun s07_number() {
        val vm = captureVm("BC0003")
        vm.photos[ImageRole.DISPLAY] = samplePhoto("30587")
        vm.next(); vm.next()
        "30587".forEach { vm.typeDigit(NumberTarget.CURRENT, it) }
        show { CaptureScreen("BC0003", onNextMeter = {}, onHome = {}, onExit = {}) }
        shot("07_capture_number")
    }

    @Test fun s08_number_warning() {
        // Sewerage meter 2002-2: last 17040, usual at most 900 a month; 19950 is far above.
        val vm = captureVm("BC0007")
        vm.photos[ImageRole.DISPLAY] = samplePhoto("19950")
        vm.next(); vm.next()
        "19950".forEach { vm.typeDigit(NumberTarget.CURRENT, it) }
        show { CaptureScreen("BC0007", onNextMeter = {}, onHome = {}, onExit = {}) }
        shot("08_capture_number_warning")
    }

    @Test fun s09_confirm_tenant() {
        val vm = captureVm("BC0003")
        vm.photos[ImageRole.DISPLAY] = samplePhoto("30587")
        vm.next(); vm.next()
        "30587".forEach { vm.typeDigit(NumberTarget.CURRENT, it) }
        vm.next()
        vm.tenantCode = "T-0102"
        show { CaptureScreen("BC0003", onNextMeter = {}, onHome = {}, onExit = {}) }
        shot("09_capture_check_tenant")
    }

    @Test fun s10_sent() {
        val vm = captureVm("BC0003")
        vm.photos[ImageRole.DISPLAY] = samplePhoto("30587")
        vm.next(); vm.next()
        "30587".forEach { vm.typeDigit(NumberTarget.CURRENT, it) }
        vm.next()
        vm.tenantCode = "T-0102"
        vm.submit()
        show { CaptureScreen("BC0003", onNextMeter = {}, onHome = {}, onExit = {}) }
        shot("10_capture_sent")
    }

    @Test fun s11_saved_no_signal() {
        repo.online.value = false
        val vm = captureVm("BC0012")
        vm.photos[ImageRole.DISPLAY] = samplePhoto("12460")
        vm.next(); vm.next()
        "12460".forEach { vm.typeDigit(NumberTarget.CURRENT, it) }
        vm.next()
        vm.tenantCode = "T-0202"
        vm.submit()
        show { CaptureScreen("BC0012", onNextMeter = {}, onHome = {}, onExit = {}) }
        shot("11_capture_saved_no_signal")
    }

    @Test fun s12_my_readings() {
        show { MyReadingsScreen(onCapture = {}, onBack = {}) }
        shot("12_my_readings")
    }

    @Test fun s13_summary() {
        show { SummaryScreen(onBack = {}) }
        shot("13_summary")
    }

    @Test fun s14_signal_back() {
        show {
            HomeScreen(onStart = {}, onFind = {}, onZones = {}, onReadings = {}, onSummary = {}, onSettings = {})
            SyncPromptDialog(readings = 3, photos = 3, onSendNow = {}, onLater = {})
        }
        shot("14_signal_back_prompt")
    }
}
