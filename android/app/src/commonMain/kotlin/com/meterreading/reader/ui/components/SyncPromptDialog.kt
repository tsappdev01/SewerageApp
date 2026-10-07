package com.meterreading.reader.ui.components

import com.meterreading.reader.platform.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import com.meterreading.reader.resources.*
import com.meterreading.reader.ui.theme.AppColors

/**
 * FR-020.4: "Signal is back" — asks the reader to send what was saved without signal, now or later.
 * One navy button (Send now); Later is plain. The read-aloud line says the same.
 */
@Composable
fun SyncPromptDialog(readings: Int, photos: Int, onSendNow: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        icon = { Icon(Icons.Rounded.CloudUpload, null, tint = AppColors.Queued, modifier = Modifier.size(40.dp)) },
        title = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(Res.string.sync_title), modifier = Modifier.weight(1f))
                SpeakButton(stringResource(Res.string.speak_sync))
            }
        },
        text = { Text(stringResource(Res.string.sync_text, readings, photos), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Button(onClick = onSendNow, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Navy), modifier = Modifier.heightIn(min = 52.dp)) {
                Icon(Icons.Rounded.CloudUpload, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.sync_now))
            }
        },
        dismissButton = { TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 52.dp)) { Text(stringResource(Res.string.sync_later)) } },
    )
}
