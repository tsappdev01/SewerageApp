package com.meterreading.reader.ui.components

import com.meterreading.reader.platform.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.NumberFont

enum class OdometerStyle { Current, Previous }

/**
 * One box per meter wheel, so the reader copies digit by digit, left to right, exactly as
 * the meter shows them (FR-006.3). [digits] may be shorter than [size] while typing.
 */
@Composable
fun Odometer(
    digits: String,
    size: Int,
    modifier: Modifier = Modifier,
    style: OdometerStyle = OdometerStyle.Current,
    showCursor: Boolean = false,
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val gap = 5.dp
        val maxCell = if (style == OdometerStyle.Current) 48.dp else 38.dp
        val cellWidth = min(maxCell, (maxWidth - gap * (size - 1)) / size)
        val cellHeight = cellWidth * if (style == OdometerStyle.Current) 1.3f else 1.15f
        val fontSize = if (style == OdometerStyle.Current) (cellWidth.value * 0.8f).sp else (cellWidth.value * 0.66f).sp
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (i in 0 until size) {
                val char = digits.getOrNull(i)
                val isCursor = showCursor && i == digits.length
                val (background, foreground, border) = when {
                    style == OdometerStyle.Previous -> Triple(Color.Transparent, AppColors.OdometerPrevious, AppColors.Line)
                    char != null -> Triple(AppColors.Odometer, Color.White, AppColors.Odometer)
                    isCursor -> Triple(AppColors.Card, AppColors.Ink, AppColors.Navy)
                    else -> Triple(AppColors.Card, AppColors.Ink, AppColors.SubInk)
                }
                Box(
                    modifier = Modifier
                        .size(cellWidth, cellHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(background)
                        .border(if (isCursor) 3.dp else 2.dp, border, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (char != null) {
                        Text(char.toString(), fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = fontSize, color = foreground)
                    }
                }
            }
        }
    }
}
