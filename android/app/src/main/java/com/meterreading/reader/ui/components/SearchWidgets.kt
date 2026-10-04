package com.meterreading.reader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meterreading.reader.R
import com.meterreading.reader.data.Search
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.NumberFont

/**
 * Search box. Shows typed text in large digits, or a placeholder. Tapping it opens the Find
 * screen; the microphone starts voice search.
 */
@Composable
fun SearchField(
    text: String,
    placeholder: String,
    onClick: () -> Unit,
    onMic: () -> Unit,
    active: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = AppColors.Card,
        border = BorderStroke(2.dp, if (active) AppColors.Navy else AppColors.Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 60.dp)
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = AppColors.SubInk, modifier = Modifier.size(28.dp))
            if (text.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.titleMedium, color = AppColors.SubInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else {
                Text(text, fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = AppColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            CircleIconButton(Icons.Rounded.Mic, stringResource(R.string.search_voice), onMic, container = AppColors.NavyTint, content = AppColors.Navy, bordered = false, size = 48.dp)
        }
    }
}

/** Filter chip with an optional icon. Selected chips are solid navy. */
@Composable
fun ChoiceChip(label: String?, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null, iconTint: Color = AppColors.Ink, description: String? = null) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) AppColors.Navy else AppColors.Card,
        contentColor = if (selected) Color.White else AppColors.Ink,
        border = BorderStroke(1.5.dp, if (selected) AppColors.Navy else AppColors.Line),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = description, tint = if (selected) Color.White else iconTint, modifier = Modifier.size(22.dp))
            if (label != null) Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Code or number with the part that matched the search highlighted. */
@Composable
fun HighlightedText(text: String, query: String, style: TextStyle, color: Color = AppColors.Ink) {
    val range = Search.highlightRange(text, query)
    val annotated = buildAnnotatedString {
        append(text)
        if (range != null) {
            addStyle(SpanStyle(background = AppColors.NavyTint, color = AppColors.Navy), range.first, range.last + 1)
        }
    }
    Text(annotated, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Keypad for search: digits, dash, delete, and ABC to open the letter keyboard for codes like W1. */
@Composable
fun SearchPad(
    onKey: (Char) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onLetters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyHeight = 50.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'))
        rows.forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { c -> PadKey(Modifier.weight(1f), keyHeight, onClick = { onKey(c) }) { DigitLabel(c.toString()) } }
                when (index) {
                    0 -> PadKey(Modifier.weight(1f), keyHeight, onClick = onDelete, content = AppColors.Bad) {
                        Icon(Icons.AutoMirrored.Rounded.Backspace, stringResource(R.string.delete_digit), Modifier.size(26.dp))
                    }
                    1 -> PadKey(Modifier.weight(1f), keyHeight, onClick = { onKey('-') }) { DigitLabel("-") }
                    else -> PadKey(Modifier.weight(1f), keyHeight, onClick = onLetters) {
                        Text(stringResource(R.string.search_letters), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PadKey(Modifier.weight(2f), keyHeight, onClick = { onKey('0') }) { DigitLabel("0") }
            PadKey(Modifier.weight(2f), keyHeight, onClick = onClear) {
                Text(stringResource(R.string.search_clear), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
