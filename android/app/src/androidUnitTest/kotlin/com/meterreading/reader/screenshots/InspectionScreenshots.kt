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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.meterreading.reader.data.AppGraph
import com.meterreading.reader.data.EvidencePhoto
import com.meterreading.reader.data.FakeInspectionApi
import com.meterreading.reader.data.FakeMeterRepository
import com.meterreading.reader.data.InspectionRepository
import com.meterreading.reader.data.UnitEntry
import com.meterreading.reader.data.UnitResult
import com.meterreading.reader.ui.inspection.InspectionPlanScreen
import com.meterreading.reader.ui.inspection.InspectionPropertyScreen
import com.meterreading.reader.ui.inspection.InspectionReviewScreen
import com.meterreading.reader.ui.inspection.InspectionUnitsScreen
import com.meterreading.reader.ui.inspection.UnitSheet
import com.meterreading.reader.ui.screens.HomeScreen
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.MeterReaderTheme
import com.meterreading.reader.util.LocalSpeaker
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.PreviewContextConfigurationEffect
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Pictures of the Field Inspection screens (spec §21) with the demo data (FakeInspectionApi).
 * Run only with -Pscreenshots, like [MeterReadingScreenshots]. Pictures go to app/build/screenshots/.
 */
@OptIn(ExperimentalRoborazziApi::class, ExperimentalResourceApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class InspectionScreenshots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var repo: InspectionRepository
    private val planId get() = repo.plans.value.first { it.propertyCode == "597-559" }.id

    @Before fun setUp() {
        AppGraph.repository = FakeMeterRepository()
        repo = InspectionRepository(FakeInspectionApi())
        AppGraph.inspections = repo
        runBlocking {
            repo.refreshPlan()
            repo.units(repo.plans.value.first { it.propertyCode == "597-559" })
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            // Robolectric does not start the provider that gives Compose resources the Android context.
            // The effect only sets it in inspection mode, so turn that on for the effect alone.
            CompositionLocalProvider(LocalInspectionMode provides true) { PreviewContextConfigurationEffect() }
            MeterReaderTheme {
                CompositionLocalProvider(LocalSpeaker provides null) {
                    Box(Modifier.fillMaxSize().background(AppColors.Background)) { content() }
                }
            }
        }
    }

    /** The demo API waits a little on its own threads, so give it real time as well as looper time. */
    private fun settle() {
        repeat(4) {
            compose.mainClock.advanceTimeBy(1_000)
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
            Thread.sleep(250)
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        settle()
        captureScreenRoboImage("build/screenshots/$name.png")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.tapEvenIfScrolled() {
        runCatching { performScrollTo() }
        performClick()
    }

    /** A stand-in photo of a shop sign (the demo has no camera). */
    private fun signPhoto(text: String): File {
        val bmp = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(196, 190, 178))
        c.drawRect(0f, 640f, 1200f, 900f, Paint().apply { color = Color.rgb(120, 112, 100) })
        c.drawRect(150f, 160f, 1050f, 400f, Paint().apply { color = Color.rgb(20, 60, 120) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 72f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        c.drawText(text, 600f, 305f, p)
        c.drawRect(480f, 440f, 720f, 900f, Paint().apply { color = Color.rgb(70, 70, 74) })
        val f = File(compose.activity.cacheDir, "sign-${text.hashCode()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f
    }

    private fun photo(text: String) = EvidencePhoto(UUID.randomUUID().toString(), signPhoto(text).path, kotlinx.datetime.Clock.System.now().toString())

    private fun entry(unitId: String, code: String, result: UnitResult?, people: Int? = null, occupant: String = "", reasons: List<String> = emptyList(), photos: List<EvidencePhoto> = emptyList()) =
        UnitEntry(UUID.randomUUID().toString(), unitId, code, "ELEGANT INDUSTRIES", "Industrial>Warehouse>Warehouse", result, people, occupant, reasons, "", photos)

    /** A visit at 597-559, started at the property, with some units recorded. */
    private fun visitInProgress(allChecked: Boolean = false) {
        val plan = repo.plans.value.first { it.id == planId }
        repo.startVisit(plan)
        repo.update(planId) { it.copy(atProperty = true, latitude = 24.98412, longitude = 55.15534, gpsAccuracyM = 6.0) }
        repo.saveEntry(planId, entry("1001", "1", UnitResult.AS_RECORDED, people = 4))
        repo.saveEntry(planId, entry("1002", "2", UnitResult.VACANT, people = 0, reasons = listOf("EMPTY")))
        repo.saveEntry(planId, entry("1003", "3", UnitResult.SUBLEASED, people = 7, occupant = "Star Auto Spare Parts", reasons = listOf("OTHER_COMPANY_SIGN"), photos = listOf(photo("STAR AUTO SPARE PARTS"))))
        if (allChecked) {
            repo.saveEntry(planId, entry("1004", "4", UnitResult.AS_RECORDED, people = 3))
            repo.saveEntry(planId, entry("1005", "5", UnitResult.PENDING, reasons = listOf("LOCKED")))
        }
    }

    @Test fun i01_home_with_inspection() {
        show { HomeScreen(onStart = {}, onFind = {}, onZones = {}, onReadings = {}, onSummary = {}, onSettings = {}) }
        shot("i01_home_field_inspection")
    }

    @Test fun i02_plan_today() {
        show { InspectionPlanScreen(onPlan = {}, onBack = {}) }
        shot("i02_plan_today")
    }

    @Test fun i03_plan_late() {
        show { InspectionPlanScreen(onPlan = {}, onBack = {}) }
        settle()
        compose.onNodeWithText("Late").performClick()
        shot("i03_plan_late")
    }

    @Test fun i04_property() {
        show { InspectionPropertyScreen(planId, onStart = {}, onBack = {}) }
        shot("i04_property")
    }

    @Test fun i05_are_you_at_the_property() {
        show { InspectionPropertyScreen(planId, onStart = {}, onBack = {}) }
        settle()
        compose.onNodeWithText("Start inspection").tapEvenIfScrolled()
        shot("i05_are_you_at_the_property")
    }

    @Test fun i06_property_in_progress() {
        visitInProgress()
        show { InspectionPropertyScreen(planId, onStart = {}, onBack = {}) }
        shot("i06_property_location_and_distance")
    }

    @Test fun i07_units() {
        visitInProgress()
        show { InspectionUnitsScreen(planId, inspector = "rashid@dip.example", onReview = {}, onBack = {}) }
        shot("i07_units")
    }

    @Test fun i08_unit_sheet_subleased() {
        visitInProgress()
        val unit = repo.cachedUnits(planId)!!.units.first { it.unitId == "1003" }
        val e = repo.draft(planId)!!.entries.getValue("1003")
        show {
            InspectionUnitsScreen(planId, inspector = "rashid@dip.example", onReview = {}, onBack = {})
            UnitSheet(e, unit, listOf("ELEGANT INDUSTRIES"), onChange = {}, onTakePhoto = {}, onSave = {}, onRemove = null, onDismiss = {})
        }
        shot("i08_unit_subleased")
    }

    @Test fun i09_unit_sheet_new() {
        visitInProgress()
        val unit = repo.cachedUnits(planId)!!.units.first { it.unitId == "1004" }
        show {
            InspectionUnitsScreen(planId, inspector = "rashid@dip.example", onReview = {}, onBack = {})
            UnitSheet(entry("1004", "4", null), unit, listOf("ELEGANT INDUSTRIES"), onChange = {}, onTakePhoto = {}, onSave = {}, onRemove = null, onDismiss = {})
        }
        shot("i09_unit_what_did_you_find")
    }

    @Test fun i10_review() {
        visitInProgress(allChecked = true)
        show { InspectionReviewScreen(planId, onNextProperty = {}, onPlanList = {}, onBack = {}) }
        shot("i10_check_and_send")
    }

    @Test fun i11_sent() {
        visitInProgress(allChecked = true)
        show { InspectionReviewScreen(planId, onNextProperty = {}, onPlanList = {}, onBack = {}) }
        settle()
        compose.onNodeWithText("Send inspection").tapEvenIfScrolled()
        settle()
        shot("i11_sent")
    }
}
