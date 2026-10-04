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
import androidx.compose.ui.platform.LocalContext
import com.meterreading.reader.auth.CompanySignInException
import com.meterreading.reader.auth.SignInCancelledException
import com.meterreading.reader.settings.AppServices
import com.meterreading.reader.util.findActivity

/**
 * Start screen. With company sign-in off (for now) the app opens straight away with the test
 * sign-in name from Settings; this screen only stays if that fails. With company sign-in on, a
 * remembered account opens straight away too; otherwise the reader taps "Sign in with company
 * account". The gear opens Settings (server address, sign-in).
 */
@Composable
fun SignInScreen(onSignedIn: () -> Unit, onSettings: () -> Unit) {
    val repo = AppGraph.repository
    val company = AppServices.companySignIn
    val online by repo.online.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()
    var login by rememberSaveable { mutableStateOf(AppServices.settings.testLogin) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(if (repo.signInNeeded.value) "Please sign in again. Your readings are kept on the phone." else null) }

    /** [interactive] false: only open if no question is needed (remembered account or test name). */
    fun signIn(interactive: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val name: String? = if (company == null) {
                login.trim().ifEmpty { null }
            } else {
                try {
                    if (interactive && activity != null) company.signIn(activity) else company.currentAccount()
                } catch (e: SignInCancelledException) {
                    null
                } catch (e: CompanySignInException) {
                    error = e.message
                    null
                }
            }
            if (name != null) {
                when (val result = repo.signIn(name)) {
                    SignInResult.Success -> onSignedIn()
                    is SignInResult.Failed -> error = result.message
                }
            }
            busy = false
        }
    }

    // Open the app directly when nothing needs asking (not after a "sign in again").
    LaunchedEffect(Unit) { if (!repo.signInNeeded.value) signIn(interactive = false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            SettingsButton(onSettings)
            SpeakButton(stringResource(if (company == null) R.string.speak_signin else R.string.speak_signin_company))
        }
        DipLogo()
        BrandBar()
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = AppColors.Navy)
            Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodyLarge, color = AppColors.SubInk)
        }
        Spacer(Modifier.height(8.dp))
        if (company == null && repo.needsDevLogin) {
            OutlinedTextField(
                value = login,
                onValueChange = { login = it.trim() },
                label = { Text(stringResource(R.string.dev_login_label)) },
                supportingText = { Text(stringResource(R.string.dev_login_hint)) },
                singleLine = true,
                enabled = !busy,
                textStyle = MaterialTheme.typography.titleMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { signIn(interactive = true) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { Banner(it, Icons.Rounded.Warning, AppColors.Bad, AppColors.BadTint) }
        BigButton(
            text = stringResource(
                when {
                    busy -> R.string.signing_in
                    company != null -> R.string.sign_in_company
                    else -> R.string.sign_in
                },
            ),
            onClick = { signIn(interactive = true) },
            icon = if (company != null) Icons.Rounded.Business else Icons.Rounded.Person,
            enabled = !busy && (company != null || !repo.needsDevLogin || login.isNotBlank()),
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppColors.Navy)
        Text(
            stringResource(if (company != null) R.string.sign_in_hint else R.string.sign_in_hint_test),
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = AppColors.SubInk,
        )
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
