package com.meterreading.reader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meterreading.reader.BuildConfig
import com.meterreading.reader.R
import com.meterreading.reader.api.ApiClient
import com.meterreading.reader.data.AppGraph
import com.meterreading.reader.data.AppSettings
import com.meterreading.reader.data.EntraSettings
import com.meterreading.reader.data.SettingsRules
import com.meterreading.reader.settings.AppServices
import com.meterreading.reader.ui.components.*
import com.meterreading.reader.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * Settings (gear icon): the server address and company sign-in (Entra ID). Usually set once by IT.
 * Saving switches the app over and goes back to the start screen.
 */
@Composable
fun SettingsScreen(onSaved: () -> Unit, onBack: () -> Unit) {
    val current = AppServices.settings
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(current.apiBaseUrl) }
    var entraOn by rememberSaveable { mutableStateOf(current.entraEnabled) }
    var testLogin by rememberSaveable { mutableStateOf(current.testLogin) }
    var tenant by rememberSaveable { mutableStateOf(current.entra.tenantId) }
    var client by rememberSaveable { mutableStateOf(current.entra.clientId) }
    var redirect by rememberSaveable { mutableStateOf(current.entra.redirectUri) }
    var apiScope by rememberSaveable { mutableStateOf(current.entra.apiScope) }
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val company = AppServices.companySignIn
    var signedInAs by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(company) { signedInAs = runCatching { company?.currentAccount() }.getOrNull() }

    fun edited() = AppSettings(url, entraOn, testLogin, EntraSettings(tenant, client, redirect, apiScope))

    fun save() {
        val found = SettingsRules.problems(edited(), AppServices.allowHttp).toMutableList()
        // Readings waiting on the phone are kept in memory only; changing server would lose them.
        if (AppGraph.repository.hasWaiting()) found.add(0, "Readings are still waiting to send. Send them before changing settings.")
        problems = found
        if (found.isNotEmpty() || saving) return
        saving = true
        scope.launch {
            AppServices.save(SettingsRules.cleaned(edited(), AppServices.allowHttp))
            saving = false
            onSaved()
        }
    }

    fun testServer() {
        val normalized = SettingsRules.normalizeUrl(url, AppServices.allowHttp)
        if (normalized == null) {
            problems = SettingsRules.problems(edited(), AppServices.allowHttp)
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
        AppTopBar(stringResource(R.string.settings_title), stringResource(R.string.speak_settings), onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_server), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim(); testResult = null },
                label = { Text(stringResource(R.string.settings_url_label)) },
                supportingText = { Text(stringResource(R.string.settings_url_hint)) },
                singleLine = true,
                enabled = !saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = ::testServer, enabled = !testing && !saving) {
                    Icon(Icons.Rounded.NetworkCheck, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (testing) R.string.settings_testing else R.string.settings_test))
                }
                when (testResult) {
                    true -> Pill(stringResource(R.string.settings_server_ok), AppColors.Ok, AppColors.OkTint, Icons.Rounded.CheckCircle)
                    false -> Pill(stringResource(R.string.settings_server_bad), AppColors.Bad, AppColors.BadTint, Icons.Rounded.ErrorOutline)
                    null -> {}
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.settings_signin), style = MaterialTheme.typography.titleLarge)
            // The whole row toggles; the words "On"/"Off" say the state, not only the switch colour.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = entraOn, enabled = !saving, role = Role.Switch) { entraOn = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_entra), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(if (entraOn) R.string.settings_on else R.string.settings_off),
                        style = MaterialTheme.typography.bodyMedium, color = AppColors.SubInk,
                    )
                }
                Switch(checked = entraOn, onCheckedChange = null, enabled = !saving)
            }

            if (entraOn) {
                Banner(stringResource(R.string.settings_entra_hint), Icons.Rounded.Info, AppColors.Navy, AppColors.NavyTint)
                SettingField(tenant, { tenant = it }, R.string.settings_tenant, "dubaiinvestments.onmicrosoft.com", !saving)
                SettingField(client, { client = it }, R.string.settings_client, "00000000-0000-0000-0000-000000000000", !saving)
                SettingField(redirect, { redirect = it }, R.string.settings_redirect, "msauth://${BuildConfig.APPLICATION_ID}/…", !saving)
                SettingField(apiScope, { apiScope = it }, R.string.settings_scope, "api://meterreading-api/access_as_user", !saving)
                if (signedInAs != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.settings_signed_in_as, signedInAs!!), modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { company?.signOut() }
                                signedInAs = null
                                onSaved()
                            }
                        }) { Text(stringResource(R.string.settings_sign_out)) }
                    }
                }
            } else {
                SettingField(testLogin, { testLogin = it }, R.string.settings_test_login, "rashid@dip.ae", !saving, KeyboardType.Email)
                Text(stringResource(R.string.settings_test_login_hint), style = MaterialTheme.typography.bodyMedium, color = AppColors.SubInk)
            }

            problems.forEach { Banner(it, Icons.Rounded.Warning, AppColors.Bad, AppColors.BadTint) }
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall, color = AppColors.SubInk,
            )
        }
        Box(Modifier.padding(16.dp)) {
            BigButton(
                text = stringResource(if (saving) R.string.settings_saving else R.string.settings_save),
                onClick = ::save,
                icon = Icons.Rounded.Save,
                enabled = !saving,
            )
        }
    }
}

@Composable
private fun SettingField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    example: String,
    enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Ascii,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        label = { Text(stringResource(label)) },
        placeholder = { Text(example) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
