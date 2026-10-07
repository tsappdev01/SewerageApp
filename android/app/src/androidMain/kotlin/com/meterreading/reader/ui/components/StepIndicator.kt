package com.meterreading.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.meterreading.reader.ui.theme.AppColors

/** Row of circles showing where the reader is in the capture flow. */
@Composable
fun StepIndicator(icons: List<ImageVector>, current: Int, modifier: Modifier = Modifier) {
    val size = if (icons.size > 6) 28.dp else 34.dp
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icons.forEachIndexed { index, icon ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(width = 10.dp, height = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (index <= current) AppColors.Ok else AppColors.Line),
                )
            }
            val (bg, fg) = when {
                index < current -> AppColors.Ok to Color.White
                index == current -> AppColors.Navy to Color.White
                else -> AppColors.Line to AppColors.SubInk
            }
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (index < current) Icons.Rounded.Check else icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.55f))
            }
        }
    }
}
