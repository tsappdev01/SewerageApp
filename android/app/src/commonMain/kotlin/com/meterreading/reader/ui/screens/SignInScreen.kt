package com.meterreading.reader.ui.screens

import com.meterreading.reader.platform.*
import androidx.compose.foundation.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meterreading.reader.resources.*
import com.meterreading.reader.data.*
import com.meterreading.reader.ui.components.*
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.NumberFont
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.painterResource
import com.meterreading.reader.settings.AppServices
import com.meterreading.reader.settings.Registration

/**
 * Start screen. Once the phone's lock is given (FR-001.1) the app asks the server about this phone
 * (FR-002.8): a phone IT has not registered yet shows its Phone ID and Check code for IT, with "Check
 * again". A registered phone opens straight to Home as the reader IT gave it. The screen otherwise
 * stays only when opening fails, with the reason, a "Try again" button and the gear for the supervisor.
 */
@Composable
fun SignInScreen(onSignedIn: () -> Unit, onSettings: () -> Unit) {
    val repo = AppGraph.repository
    val locked by AppServices.locked.collectAsStateWithLifecycle()
    val online by repo.online.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf(AppServices.settings.readerLogin) }
    var waiting by remember { mutableStateOf<Registration.Waiting?>(null) }
    var busy by remember { mutableStateOf(false) }
    val noReader = stringResource(Res.string.start_no_reader)
    val notAccepted = stringResource(Res.string.start_not_accepted)
    val blocked = stringResource(Res.string.start_blocked)
    var error by remember { mutableStateOf<String?>(if (repo.signInNeeded.value) repo.signInReason ?: notAccepted else null) }

    fun open() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            when (val registration = AppServices.checkRegistration()) {
                is Registration.Waiting -> { waiting = registration; busy = false; return@launch }
                Registration.Blocked -> { waiting = null; error = blocked; busy = false; return@launch }
                else -> waiting = null // registered, or no signal: open as before
            }
            login = AppServices.settings.readerLogin // IT's reader, when the server sent one
            if (login.isBlank()) {
                error = noReader
                busy = false
                return@launch
            }
            when (val result = AppGraph.repository.signIn(login)) {
                SignInResult.Success -> onSignedIn()
                is SignInResult.Failed -> error = result.message
            }
            busy = false
        }
    }

    // Open the app directly once unlocked (not after the server refused this reader).
    LaunchedEffect(locked) { if (!locked && !repo.signInNeeded.value) open() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            SettingsButton(onSettings)
            SpeakButton(stringResource(if (waiting != null) Res.string.speak_waiting else Res.string.speak_signin))
        }
        DipLogo()
        BrandBar()
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineLarge, color = AppColors.Navy)
            Text(stringResource(Res.string.app_subtitle), style = MaterialTheme.typography.bodyLarge, color = AppColors.SubInk)
        }
        if (login.isNotBlank()) {
            Text(
                stringResource(Res.string.start_phone_of, login),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = AppColors.SubInk,
            )
        }
        waiting?.let { WaitingForIt(it) }
        error?.let { Banner(it, Icons.Rounded.Warning, AppColors.Bad, AppColors.BadTint) }
        BigButton(
            text = stringResource(
                when {
                    busy -> Res.string.signing_in
                    waiting != null -> Res.string.start_check_again
                    else -> Res.string.start_open
                },
            ),
            onClick = ::open,
            icon = Icons.AutoMirrored.Rounded.Login,
            enabled = !busy,
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppColors.Navy)
        if (repo.isDemo) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(checked = !online, onCheckedChange = { repo.online.value = !it })
                Text(stringResource(Res.string.demo_no_signal), color = AppColors.SubInk)
            }
        }
    }
}

/** The Dubai Investments Park logo (res/drawable-nodpi/dip_logo.png, made from docs/Dubai-Investments-Park (8).jpg). */
@Composable
fun DipLogo() {
    Image(
        painter = painterResource(Res.drawable.dip_logo),
        contentDescription = stringResource(Res.string.logo_description),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp),
    )
}

/** Taupe and navy strip taken from the logo's two colours. */
@Composable
fun BrandBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        Box(Modifier.weight(0.3f).fillMaxHeight().background(AppColors.Taupe))
        Box(Modifier.weight(0.7f).fillMaxHeight().background(AppColors.Navy))
    }
}

/** FR-002.8: what IT needs to register this phone (db/ops/approve_device.sql). */
@Composable
private fun WaitingForIt(w: Registration.Waiting) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.WarnTint)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.HourglassTop, null, tint = AppColors.Warn, modifier = Modifier.size(32.dp))
            Text(stringResource(Res.string.start_waiting_title), style = MaterialTheme.typography.titleLarge, color = AppColors.Ink)
        }
        Text(stringResource(Res.string.start_waiting_text), style = MaterialTheme.typography.bodyLarge, color = AppColors.Ink)
        PhoneIdLines(w.phoneId, w.checkCode)
    }
}

/** Phone ID and Check code, large and selectable, so they can be read out or copied for IT. */
@Composable
fun PhoneIdLines(phoneId: String, checkCode: String) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.phone_id_label), style = MaterialTheme.typography.labelMedium, color = AppColors.SubInk)
            Text(phoneId.ifBlank { "–" }, fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = AppColors.Ink)
            if (checkCode.isNotBlank()) {
                Text(stringResource(Res.string.check_code_label), style = MaterialTheme.typography.labelMedium, color = AppColors.SubInk)
                Text(checkCode, fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 3.sp, color = AppColors.Navy)
            }
        }
    }
}
