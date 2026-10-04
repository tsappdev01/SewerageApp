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

@Composable
fun SignInScreen(onSignedIn: () -> Unit) {
    val repo = AppGraph.repository
    val online by repo.online.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { SpeakButton(stringResource(R.string.speak_signin)) }
        // TODO(brand): replace with the Dubai Investments Park logo (res/drawable/dip_logo.png) once supplied.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.TaupeTint)
                .border(2.dp, AppColors.Taupe, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.logo_placeholder), color = AppColors.Taupe, style = MaterialTheme.typography.titleMedium)
        }
        BrandBar()
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = AppColors.Navy)
            Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodyLarge, color = AppColors.SubInk)
        }
        Spacer(Modifier.height(24.dp))
        // TODO(FR-001.1): Entra ID sign-in with MSAL (Authorization Code + PKCE).
        BigButton(stringResource(R.string.sign_in), onSignedIn, icon = Icons.Rounded.Person)
        Text(stringResource(R.string.sign_in_hint), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = AppColors.SubInk)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(checked = !online, onCheckedChange = { repo.online.value = !it })
            Text(stringResource(R.string.demo_no_signal), color = AppColors.SubInk)
        }
    }
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
