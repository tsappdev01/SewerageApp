package com.meterreading.reader.ui.components

import com.meterreading.reader.platform.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meterreading.reader.resources.*
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.NumberFont

/** Big in-app keypad: no system keyboard, no letters, no auto-correct (FR-006.3). */
@Composable
fun NumberPad(
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    doneEnabled: Boolean,
    modifier: Modifier = Modifier,
    keyHeight: Dp = 60.dp,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    PadKey(Modifier.weight(1f), keyHeight, onClick = { onDigit(c) }) { DigitLabel(c.toString()) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadKey(Modifier.weight(1f), keyHeight, onClick = onDelete, content = AppColors.Bad) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, stringResource(Res.string.delete_digit), Modifier.size(30.dp))
            }
            PadKey(Modifier.weight(1f), keyHeight, onClick = { onDigit('0') }) { DigitLabel("0") }
            PadKey(
                Modifier.weight(1f), keyHeight, onClick = onDone, enabled = doneEnabled,
                container = AppColors.Navy, content = Color.White,
            ) {
                Icon(Icons.Rounded.Check, stringResource(Res.string.done), Modifier.size(32.dp))
            }
        }
    }
}

@Composable
fun DigitLabel(text: String) {
    Text(text, fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 28.sp)
}

@Composable
fun PadKey(
    modifier: Modifier,
    height: Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
    container: Color = AppColors.Card,
    content: Color = AppColors.Ink,
    label: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier.height(height),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) container else AppColors.Line,
        contentColor = if (enabled) content else AppColors.SubInk,
        border = BorderStroke(1.5.dp, if (container == AppColors.Card) AppColors.Line else container),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { label() }
    }
}
