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

@Composable
fun SignInScreen(onSignedIn: () -> Unit, defaultLogin: String = "") {
    val repo = AppGraph.repository
    val online by repo.online.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var login by rememberSaveable { mutableStateOf(defaultLogin) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun signIn() {
        if (busy) return
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { SpeakButton(stringResource(R.string.speak_signin)) }
        DipLogo()
        BrandBar()
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = AppColors.Navy)
            Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodyLarge, color = AppColors.SubInk)
        }
        Spacer(Modifier.height(8.dp))
        if (repo.needsDevLogin) {
            // TODO(FR-001.1): Entra ID sign-in with MSAL replaces this field.
            OutlinedTextField(
                value = login,
                onValueChange = { login = it.trim() },
                label = { Text(stringResource(R.string.dev_login_label)) },
                supportingText = { Text(stringResource(R.string.dev_login_hint)) },
                singleLine = true,
                enabled = !busy,
                textStyle = MaterialTheme.typography.titleMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { signIn() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { Banner(it, Icons.Rounded.Warning, AppColors.Bad, AppColors.BadTint) }
        BigButton(
            text = stringResource(if (busy) R.string.signing_in else R.string.sign_in),
            onClick = { signIn() },
            icon = Icons.Rounded.Person,
            enabled = !busy && (!repo.needsDevLogin || login.isNotBlank()),
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppColors.Navy)
        Text(stringResource(R.string.sign_in_hint), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = AppColors.SubInk)
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
