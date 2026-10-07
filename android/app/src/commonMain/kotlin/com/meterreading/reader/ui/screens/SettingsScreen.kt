package com.meterreading.reader.ui.screens

import com.meterreading.reader.platform.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meterreading.reader.resources.*
import com.meterreading.reader.api.ApiClient
import com.meterreading.reader.api.ApiException
import com.meterreading.reader.data.AppGraph
import com.meterreading.reader.data.AppSettings
import com.meterreading.reader.data.SettingsRules
import com.meterreading.reader.settings.AppServices
import com.meterreading.reader.ui.components.*
import com.meterreading.reader.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * Settings (gear icon, after the supervisor PIN): the server address, the reader this phone belongs
 * to, the phone lock and the supervisor PIN. Saving switches the app over and opens it again.
 */
@Composable
fun SettingsScreen(onSaved: () -> Unit, onBack: () -> Unit) {
    val current = AppServices.settings
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(current.apiBaseUrl) }
    var reader by rememberSaveable { mutableStateOf(current.readerLogin) }
    var lockOn by rememberSaveable { mutableStateOf(current.deviceLock) }
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }
    var changingPin by remember { mutableStateOf(false) }
    var pinChanged by remember { mutableStateOf(false) }
    val deviceUnlock = rememberDeviceUnlock()
    val phoneHasLock = remember { deviceUnlock.isSetUp() }
    var code by rememberSaveable { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    var registerError by remember { mutableStateOf<String?>(null) }
    val saveServerFirst = stringResource(Res.string.device_save_server_first)
    val noConnection = stringResource(Res.string.device_no_connection)

    /** Registers against the saved server; the app then opens again with the phone's key. */
    fun register() {
        if (registering) return
        if (SettingsRules.normalizeUrl(url, AppServices.allowHttp) != current.apiBaseUrl) {
            registerError = saveServerFirst
            return
        }
        registering = true
        registerError = null
        scope.launch {
            try {
                AppServices.registerDevice(current.apiBaseUrl, code)
                code = ""
                onSaved()
            } catch (e: ApiException) {
                registerError = e.title
            } catch (e: okio.IOException) {
                registerError = noConnection
            }
            registering = false
        }
    }

    fun edited() = AppSettings(url, reader, lockOn)

    fun save() {
        val found = SettingsRules.problems(edited(), AppServices.allowHttp).toMutableList()
        // Readings waiting on the phone are kept in memory only; changing server or reader would lose them.
        val changesWho = SettingsRules.cleaned(edited(), AppServices.allowHttp).let { it.apiBaseUrl != current.apiBaseUrl || it.readerLogin != current.readerLogin }
        if (changesWho && AppGraph.hasWaiting()) found.add(0, "Readings or inspections are still waiting to send. Send them before changing the server or reader.")
        problems = found
        if (found.isNotEmpty()) return
        AppServices.save(SettingsRules.cleaned(edited(), AppServices.allowHttp))
        onSaved()
    }

    fun testServer() {
        val normalized = SettingsRules.normalizeUrl(url, AppServices.allowHttp)
        if (normalized == null) {
            problems = SettingsRules.problems(edited(), AppServices.allowHttp).take(1)
            return
        }
        testing = true
        testResult = null
        scope.launch {
            testResult = runCatching { ApiClient(normalized).isReachable() }.getOrDefault(false)
            testing = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(Res.string.settings_title), stringResource(Res.string.speak_settings), onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.settings_server), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim(); testResult = null },
                label = { Text(stringResource(Res.string.settings_url_label)) },
                supportingText = { Text(stringResource(Res.string.settings_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = ::testServer, enabled = !testing) {
                    Icon(Icons.Rounded.NetworkCheck, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (testing) Res.string.settings_testing else Res.string.settings_test))
                }
                when (testResult) {
                    true -> Pill(stringResource(Res.string.settings_server_ok), AppColors.Ok, AppColors.OkTint, Icons.Rounded.CheckCircle)
                    false -> Pill(stringResource(Res.string.settings_server_bad), AppColors.Bad, AppColors.BadTint, Icons.Rounded.ErrorOutline)
                    null -> {}
                }
            }

            HorizontalDivider()
            // FR-002: this phone's registration. The key itself is never shown.
            Text(stringResource(Res.string.device_title), style = MaterialTheme.typography.titleLarge)
            val device = AppServices.device
            if (device != null) {
                Pill(stringResource(Res.string.device_registered, device.label.ifBlank { device.deviceId.take(8) }), AppColors.Ok, AppColors.OkTint, Icons.Rounded.VerifiedUser)
            } else {
                Pill(stringResource(Res.string.device_not_registered), AppColors.Warn, AppColors.WarnTint, Icons.Rounded.Warning)
            }
            OutlinedTextField(
                value = code,
                onValueChange = { text -> code = text.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(14) },
                label = { Text(stringResource(Res.string.device_code_label)) },
                supportingText = { Text(stringResource(if (device == null) Res.string.device_code_hint else Res.string.device_code_hint_again)) },
                placeholder = { Text("ABCD-EFGH-JKLM") },
                singleLine = true,
                enabled = !registering,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = ::register, enabled = !registering && code.count { it.isLetterOrDigit() } == 12, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Rounded.PhonelinkLock, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (registering) Res.string.device_registering else Res.string.device_register))
            }
            registerError?.let { Banner(it, Icons.Rounded.Warning, AppColors.Bad, AppColors.BadTint) }

            HorizontalDivider()
            Text(stringResource(Res.string.settings_reader), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = reader,
                onValueChange = { reader = it.trim() },
                label = { Text(stringResource(Res.string.settings_reader_label)) },
                supportingText = { Text(stringResource(Res.string.settings_reader_hint)) },
                placeholder = { Text("rashid@dip.ae") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            Text(stringResource(Res.string.settings_security), style = MaterialTheme.typography.titleLarge)
            // The whole row toggles; the words say the state, not only the switch colour.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = lockOn, role = Role.Switch) { lockOn = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.settings_lock), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(if (lockOn) Res.string.settings_lock_on else Res.string.settings_lock_off),
                        style = MaterialTheme.typography.bodyMedium, color = AppColors.SubInk,
                    )
                }
                Switch(checked = lockOn, onCheckedChange = null)
            }
            if (lockOn && !phoneHasLock) {
                Banner(stringResource(Res.string.lock_none), Icons.Rounded.Warning, AppColors.Warn, AppColors.WarnTint)
            }
            OutlinedButton(onClick = { changingPin = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Rounded.Password, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.settings_change_pin))
            }
            if (pinChanged) Pill(stringResource(Res.string.settings_pin_changed), AppColors.Ok, AppColors.OkTint, Icons.Rounded.CheckCircle)

            problems.forEach { Banner(it, Icons.Rounded.Warning, AppColors.Bad, AppColors.BadTint) }
            Text(
                stringResource(Res.string.settings_version, AppServices.versionName),
                style = MaterialTheme.typography.bodySmall, color = AppColors.SubInk,
            )
        }
        Box(Modifier.padding(16.dp)) {
            BigButton(text = stringResource(Res.string.settings_save), onClick = ::save, icon = Icons.Rounded.Save)
        }
    }

    if (changingPin) {
        SupervisorPinDialog(
            create = true,
            onVerified = { changingPin = false; pinChanged = true },
            onDismiss = { changingPin = false },
        )
    }
}
