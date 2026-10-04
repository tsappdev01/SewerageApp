package com.meterreading.reader.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meterreading.reader.data.AppGraph
import com.meterreading.reader.data.ReadingState
import kotlinx.coroutines.delay
import com.meterreading.reader.ui.capture.CaptureScreen
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

    fun zone(code: String) = "zone/${Uri.encode(code)}"
    fun property(code: String) = "property/${Uri.encode(code)}"
    fun capture(id: String) = "capture/${Uri.encode(id)}"
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

    fun toStart() = nav.navigate(Routes.SIGN_IN) { popUpTo(nav.graph.id) { inclusive = true } }

    // Readings saved without signal go up by themselves when signal returns (spec §9).
    // TODO(FR-020.4): WorkManager job with network constraint instead of the UI.
    LaunchedEffect(repo, online) { if (online) repo.sendQueued() }
    // While readings wait on the phone, try again every minute (signal may be back).
    LaunchedEffect(repo) {
        while (true) {
            delay(60_000)
            if (repo.hasWaiting()) repo.sendQueued()
        }
    }
    // Company sign-in ran out: back to the start screen; waiting readings stay on the phone.
    LaunchedEffect(repo) {
        repo.signInNeeded.collect { needed ->
            if (needed && nav.currentDestination?.route != Routes.SIGN_IN) toStart()
        }
    }

    NavHost(nav, startDestination = Routes.SIGN_IN) {
        composable(Routes.SIGN_IN) {
            SignInScreen(
                onSignedIn = { nav.navigate(Routes.HOME) { popUpTo(Routes.SIGN_IN) { inclusive = true } } },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onStart = { nav.navigate(Routes.capture(it)) },
                onFind = { text -> nav.navigate(Routes.search(null, text)) },
                onZones = { nav.navigate(Routes.ZONES) },
                onReadings = { nav.navigate(Routes.READINGS) },
                onSummary = { nav.navigate(Routes.SUMMARY) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
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
}
