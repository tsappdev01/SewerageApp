package com.meterreading.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.NumberFont
import com.meterreading.reader.util.formatReading

enum class ReadingStyle { Current, Previous }

/**
 * A reading as a plain number, without the meter's leading zeros ("2867", not "00002867" or "2,867"). Right-aligned
 * like a calculator: while typing, each digit comes in from the right. [digits] empty shows "0" in grey.
 */
@Composable
fun ReadingDisplay(digits: String, modifier: Modifier = Modifier, style: ReadingStyle = ReadingStyle.Current) {
    val value = digits.toLongOrNull()
    val current = style == ReadingStyle.Current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (current) 76.dp else 56.dp)
            .clip(shape)
            .background(if (current) AppColors.Card else AppColors.Background)
            .border(if (current) 3.dp else 1.dp, if (current) AppColors.Navy else AppColors.Line, shape)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            value?.let(::formatReading) ?: "0",
            fontFamily = NumberFont,
            fontWeight = FontWeight.Bold,
            fontSize = if (current) 44.sp else 30.sp,
            maxLines = 1,
            color = when {
                !current -> AppColors.OdometerPrevious
                value == null -> AppColors.Line
                else -> AppColors.Ink
            },
        )
    }
}
