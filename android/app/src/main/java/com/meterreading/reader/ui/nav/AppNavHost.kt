package com.meterreading.reader.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.currentBackStackEntryAsState
import com.meterreading.reader.data.SyncPrompt
import com.meterreading.reader.settings.Connectivity
import com.meterreading.reader.ui.components.SyncPromptDialog
import kotlinx.coroutines.launch
import java.time.Instant
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meterreading.reader.data.AppGraph
import com.meterreading.reader.data.ReadingState
import kotlinx.coroutines.delay
import com.meterreading.reader.settings.AppServices
import com.meterreading.reader.ui.capture.CaptureScreen
import com.meterreading.reader.ui.components.SupervisorPinDialog
import com.meterreading.reader.ui.screens.*

private object Routes {
    const val SIGN_IN = "signin"
    const val HOME = "home"
    const val ZONES = "zones"
    const val ZONE = "zone/{code}"
    const val PROPERTY = "property/{code}"
    const val SEARCH = "search?zone={zone}&text={text}"
    const val CAPTURE = "capture/{meterId}"
    const val READINGS = "readings"
    const val SUMMARY = "summary"
    const val SETTINGS = "settings"
    const val INSPECTIONS = "inspections"
    const val INSPECTION = "inspection/{id}"
    const val INSPECTION_UNITS = "inspection/{id}/units"
    const val INSPECTION_REVIEW = "inspection/{id}/review"

    fun zone(code: String) = "zone/${Uri.encode(code)}"
    fun property(code: String) = "property/${Uri.encode(code)}"
    fun capture(id: String) = "capture/${Uri.encode(id)}"
    fun inspection(id: String) = "inspection/${Uri.encode(id)}"
    fun inspectionUnits(id: String) = "inspection/${Uri.encode(id)}/units"
    fun inspectionReview(id: String) = "inspection/${Uri.encode(id)}/review"
    fun search(zone: String?, text: String?) =
        "search?zone=${Uri.encode(zone.orEmpty())}&text=${Uri.encode(text.orEmpty())}"
}

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    // Saving Settings replaces the repository (new server or sign-in); follow it.
    val current by AppGraph.current.collectAsStateWithLifecycle()
    val repo = current ?: return
    val online by repo.online.collectAsStateWithLifecycle()
    val locked by AppServices.locked.collectAsStateWithLifecycle()
    // Settings opens only after the supervisor PIN.
    var askPin by remember { mutableStateOf(false) }
    val openSettings = { askPin = true }

    fun toStart() = nav.navigate(Routes.SIGN_IN) { popUpTo(nav.graph.id) { inclusive = true } }

    // FR-020.4: readings saved without signal are never sent silently. When signal is back the
    // reader is asked "Send now" or "Later" (with the app closed, UploadWorker shows a notification).
    val connected by Connectivity.connected.collectAsStateWithLifecycle()
    val readings by repo.readings.collectAsStateWithLifecycle()
    val photosWaiting by repo.photosWaiting.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    // No "send now?" dialog in the middle of a reading or an inspection.
    val capturing = backStack?.destination?.route in setOf(Routes.CAPTURE, Routes.INSPECTION_UNITS, Routes.INSPECTION_REVIEW)
    var minute by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); minute++ } } // "Later" runs out while connected
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Inspections wait in the same way and are sent with the readings.
    val inspections by AppGraph.inspectionsFlow.collectAsStateWithLifecycle()
    val noInspections = remember { kotlinx.coroutines.flow.MutableStateFlow(0) }
    val waitingVisits by (inspections?.waitingVisits ?: noInspections).collectAsStateWithLifecycle()
    val waitingInspectionPhotos by (inspections?.waitingPhotos ?: noInspections).collectAsStateWithLifecycle()
    val queuedReadings = readings.count { it.state == ReadingState.QUEUED } + waitingVisits
    // The demo has no real network: its "no signal" switch stands in.
    val signal = if (repo.isDemo) online else connected
    val askToSend = remember(signal, queuedReadings, photosWaiting, waitingInspectionPhotos, capturing, locked, sending, minute) {
        !locked && !sending && SyncPrompt.shouldAsk(AppGraph.hasWaiting(), signal, AppServices.snoozedUntil, Instant.now(), capturing)
    }
    // The server did not accept this reader: back to the start screen; waiting readings stay on the phone.
    LaunchedEffect(repo) {
        repo.signInNeeded.collect { needed ->
            if (needed && nav.currentDestination?.route != Routes.SIGN_IN) toStart()
        }
    }

    Box(Modifier.fillMaxSize()) {
    NavHost(nav, startDestination = Routes.SIGN_IN) {
        composable(Routes.SIGN_IN) {
            SignInScreen(
                onSignedIn = { nav.navigate(Routes.HOME) { popUpTo(Routes.SIGN_IN) { inclusive = true } } },
                onSettings = openSettings,
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onStart = { nav.navigate(Routes.capture(it)) },
                onFind = { text -> nav.navigate(Routes.search(null, text)) },
                onZones = { nav.navigate(Routes.ZONES) },
                onReadings = { nav.navigate(Routes.READINGS) },
                onSummary = { nav.navigate(Routes.SUMMARY) },
                onSettings = openSettings,
                onInspections = { nav.navigate(Routes.INSPECTIONS) },
            )
        }
        // Field inspection (spec §21).
        composable(Routes.INSPECTIONS) {
            com.meterreading.reader.ui.inspection.InspectionPlanScreen(
                onPlan = { nav.navigate(Routes.inspection(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.INSPECTION) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            com.meterreading.reader.ui.inspection.InspectionPropertyScreen(
                planId = id,
                onStart = { nav.navigate(Routes.inspectionUnits(id)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.INSPECTION_UNITS) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            com.meterreading.reader.ui.inspection.InspectionUnitsScreen(
                planId = id,
                inspector = AppServices.settings.readerLogin,
                onReview = { nav.navigate(Routes.inspectionReview(id)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.INSPECTION_REVIEW) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            com.meterreading.reader.ui.inspection.InspectionReviewScreen(
                planId = id,
                onNextProperty = { next -> nav.navigate(Routes.inspection(next)) { popUpTo(Routes.INSPECTIONS) } },
                onPlanList = { nav.popBackStack(Routes.INSPECTIONS, inclusive = false) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onSaved = { toStart() }, onBack = { nav.popBackStack() })
        }
        composable(Routes.ZONES) {
            ZonesScreen(onZone = { nav.navigate(Routes.zone(it)) }, onBack = { nav.popBackStack() })
        }
        composable(Routes.ZONE) { entry ->
            PropertiesScreen(
                zoneCode = entry.arguments?.getString("code").orEmpty(),
                onProperty = { nav.navigate(Routes.property(it)) },
                onFind = { zone, text -> nav.navigate(Routes.search(zone, text)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.PROPERTY) { entry ->
            MetersScreen(
                propertyCode = entry.arguments?.getString("code").orEmpty(),
                onMeter = { nav.navigate(Routes.capture(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.SEARCH,
            arguments = listOf(
                navArgument("zone") { type = NavType.StringType; defaultValue = "" },
                navArgument("text") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            SearchScreen(
                zoneCode = entry.arguments?.getString("zone")?.ifEmpty { null },
                initialText = entry.arguments?.getString("text")?.ifEmpty { null },
                onProperty = { nav.navigate(Routes.property(it)) },
                onMeter = { nav.navigate(Routes.capture(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.CAPTURE, arguments = listOf(navArgument("meterId") { type = NavType.StringType })) { entry ->
            CaptureScreen(
                meterId = entry.arguments?.getString("meterId").orEmpty(),
                onNextMeter = { id -> nav.navigate(Routes.capture(id)) { popUpTo(Routes.HOME) } },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onExit = { nav.popBackStack() },
            )
        }
        composable(Routes.READINGS) {
            MyReadingsScreen(onCapture = { nav.navigate(Routes.capture(it)) }, onBack = { nav.popBackStack() })
        }
        composable(Routes.SUMMARY) {
            SummaryScreen(onBack = { nav.popBackStack() })
        }
    }
    // FR-001.1: the phone's lock covers everything until given; the screens behind keep their state.
    if (locked) LockScreen(onSettings = openSettings)
    }
    if (askToSend) {
        SyncPromptDialog(
            readings = queuedReadings,
            photos = photosWaiting + waitingInspectionPhotos,
            onSendNow = {
                sending = true
                scope.launch {
                    AppGraph.sendAll()
                    // Signal dropped again before all went up: ask again a little later.
                    if (AppGraph.hasWaiting()) AppServices.snooze(Instant.now(), SyncPrompt.RETRY)
                    sending = false
                }
            },
            onLater = { AppServices.snooze(Instant.now()) },
        )
    }
    if (askPin) {
        SupervisorPinDialog(
            onVerified = {
                askPin = false
                // A supervisor with the PIN may open Settings even from the lock screen (e.g. to switch the lock off).
                if (locked) AppServices.markUnlocked()
                nav.navigate(Routes.SETTINGS)
            },
            onDismiss = { askPin = false },
        )
    }
}
