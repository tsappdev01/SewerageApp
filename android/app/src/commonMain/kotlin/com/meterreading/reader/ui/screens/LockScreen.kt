package com.meterreading.reader.ui.screens

import com.meterreading.reader.platform.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meterreading.reader.resources.*
import com.meterreading.reader.settings.AppServices
import com.meterreading.reader.ui.components.*
import com.meterreading.reader.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * FR-001.1: covers the app until the phone's own lock (PIN, pattern, fingerprint or face) is given.
 * Drawn over the screens rather than instead of them, so a capture in progress is kept.
 */
@Composable
fun LockScreen(onSettings: () -> Unit) {
    val deviceUnlock = rememberDeviceUnlock()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<UnlockResult?>(null) }
    val title = stringResource(Res.string.lock_prompt_title)
    val subtitle = stringResource(Res.string.lock_prompt_subtitle)

    fun unlock() {
        if (busy) return
        busy = true
        scope.launch {
            when (val result = deviceUnlock.unlock(title, subtitle)) {
                UnlockResult.Unlocked -> AppServices.markUnlocked()
                UnlockResult.Cancelled -> problem = null
                else -> problem = result
            }
            busy = false
        }
    }

    // Ask straight away; the reader only sees this screen if they close the prompt.
    LaunchedEffect(Unit) { unlock() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            // Swallow taps so nothing behind the lock can be used.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { SpeakButton(stringResource(Res.string.speak_lock)) }
        DipLogo()
        BrandBar()
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Lock, null, tint = AppColors.Navy, modifier = Modifier.size(56.dp))
            Text(stringResource(Res.string.lock_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(stringResource(Res.string.lock_hint), style = MaterialTheme.typography.bodyLarge, color = AppColors.SubInk, textAlign = TextAlign.Center)
        }
        when (val p = problem) {
            UnlockResult.NoLockSet -> {
                Banner(stringResource(Res.string.lock_none), Icons.Rounded.Warning, AppColors.Bad, AppColors.BadTint)
                OutlinedButton(onClick = { deviceUnlock.openSecuritySettings() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Rounded.Settings, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.lock_open_phone_settings))
                }
                // A supervisor can still reach Settings (with their PIN) to switch the lock off.
                TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.lock_supervisor)) }
            }
            is UnlockResult.Failed -> Banner(p.message, Icons.Rounded.Warning, AppColors.Bad, AppColors.BadTint)
            else -> {}
        }
        BigButton(
            text = stringResource(Res.string.lock_unlock),
            onClick = ::unlock,
            icon = Icons.Rounded.Fingerprint,
            enabled = !busy,
        )
    }
}
