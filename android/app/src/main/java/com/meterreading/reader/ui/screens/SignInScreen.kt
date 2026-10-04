package com.meterreading.reader.ui.screens

import androidx.compose.foundation.*
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meterreading.reader.R
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
import androidx.compose.ui.res.painterResource
import com.meterreading.reader.settings.AppServices

/**
 * Start screen. Once the phone's lock is given (FR-001.1) the app opens straight to Home as the
 * reader this phone belongs to (Settings). The screen only stays when that fails, with the reason,
 * a "Try again" button and the gear for the supervisor.
 */
@Composable
fun SignInScreen(onSignedIn: () -> Unit, onSettings: () -> Unit) {
    val repo = AppGraph.repository
    val locked by AppServices.locked.collectAsStateWithLifecycle()
    val online by repo.online.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val login = AppServices.settings.readerLogin
    var busy by remember { mutableStateOf(false) }
    val noReader = stringResource(R.string.start_no_reader)
    val notAccepted = stringResource(R.string.start_not_accepted)
    var error by remember { mutableStateOf<String?>(if (repo.signInNeeded.value) notAccepted else null) }

    fun open() {
        if (busy) return
        if (login.isBlank()) {
            error = noReader
            return
        }
        busy = true
        error = null
        scope.launch {
            when (val result = repo.signIn(login)) {
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
            SpeakButton(stringResource(R.string.speak_signin))
        }
        DipLogo()
        BrandBar()
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = AppColors.Navy)
            Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodyLarge, color = AppColors.SubInk)
        }
        if (login.isNotBlank()) {
            Text(
                stringResource(R.string.start_phone_of, login),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = AppColors.SubInk,
            )
        }
        error?.let { Banner(it, Icons.Rounded.Warning, AppColors.Bad, AppColors.BadTint) }
        BigButton(
            text = stringResource(if (busy) R.string.signing_in else R.string.start_open),
            onClick = ::open,
            icon = Icons.AutoMirrored.Rounded.Login,
            enabled = !busy,
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppColors.Navy)
        if (repo.isDemo) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(checked = !online, onCheckedChange = { repo.online.value = !it })
                Text(stringResource(R.string.demo_no_signal), color = AppColors.SubInk)
            }
        }
    }
}

/** The Dubai Investments Park logo (res/drawable-nodpi/dip_logo.png, made from docs/Dubai-Investments-Park (8).jpg). */
@Composable
fun DipLogo() {
    Image(
        painter = painterResource(R.drawable.dip_logo),
        contentDescription = stringResource(R.string.logo_description),
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
