package com.meterreading.reader.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meterreading.reader.R
import com.meterreading.reader.data.SupervisorPin
import com.meterreading.reader.settings.AppServices
import com.meterreading.reader.ui.theme.AppColors

/**
 * Asks for the supervisor PIN before Settings opens. With [create] (or when no PIN exists yet) it
 * sets a new PIN instead: typed twice. Wrong PINs are counted; after five, entry waits a minute.
 */
@Composable
fun SupervisorPinDialog(onVerified: () -> Unit, onDismiss: () -> Unit, create: Boolean = false) {
    val creating = create || !AppServices.hasPin()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val needDigits = stringResource(R.string.pin_digits)
    val noMatch = stringResource(R.string.pin_no_match)
    val wrongTemplate = stringResource(R.string.pin_wrong)
    val blockedTemplate = stringResource(R.string.pin_blocked)

    fun submit() {
        if (!SupervisorPin.isValid(pin)) { message = needDigits; return }
        if (creating) {
            if (pin != confirm) { message = noMatch; return }
            AppServices.setPin(pin)
            onVerified()
            return
        }
        when (val r = AppServices.checkPin(pin)) {
            is SupervisorPin.Result.Ok -> onVerified()
            is SupervisorPin.Result.Wrong -> { message = wrongTemplate.format(r.triesLeft); pin = "" }
            is SupervisorPin.Result.Blocked -> { message = blockedTemplate.format(r.secondsLeft); pin = "" }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (creating) R.string.pin_create_title else R.string.pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (creating) R.string.pin_create_hint else R.string.pin_hint), color = AppColors.SubInk)
                PinField(pin, { pin = it }, stringResource(R.string.pin_label))
                if (creating) PinField(confirm, { confirm = it }, stringResource(R.string.pin_confirm))
                message?.let { Text(it, color = AppColors.Bad, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { Button(onClick = ::submit, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Navy)) { Text(stringResource(R.string.pin_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pin_cancel)) } },
    )
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(8)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}
