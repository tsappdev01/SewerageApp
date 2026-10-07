package com.meterreading.reader.ui.theme

import com.meterreading.reader.platform.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Dubai Investments Park palette. Navy (logo wordmark) is the one main action on each
 * screen; taupe (logo mark) is the brand accent and the sewerage colour. Semantic colours
 * are kept apart from navy so "blue button" always means "go".
 * The app is light-only on purpose: it is used outdoors in bright sun.
 */
object AppColors {
    val Navy = Color(0xFF12305C)
    val NavyDark = Color(0xFF081B38)
    val NavyTint = Color(0xFFE6ECF5)
    val Taupe = Color(0xFF776759)
    val TaupeTint = Color(0xFFEFEBE6)

    val Background = Color(0xFFF8F7F4)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF14161A)
    val SubInk = Color(0xFF4A4F57)
    val Line = Color(0xFFDEDDD4)

    val Ok = Color(0xFF17803D)
    val OkTint = Color(0xFFE3F4E8)
    val Warn = Color(0xFFB35C00)
    val WarnTint = Color(0xFFFFF0DC)
    val Bad = Color(0xFFC42B1C)
    val BadTint = Color(0xFFFDE6E3)
    val Queued = Color(0xFF6B4FBB)
    val QueuedTint = Color(0xFFEEE9FA)

    // Field inspection results (spec FR-032). Each also has its own icon and word.
    val Sublet = Color(0xFF8E3B8A)
    val SubletTint = Color(0xFFF6E7F4)
    val Vacant = Color(0xFF0F6E8C)
    val VacantTint = Color(0xFFE1F1F6)
    val PendingTint = Color(0xFFECEEF1)

    val Irrigation = Color(0xFF2E8B3E)
    val IrrigationTint = Color(0xFFE5F3E7)
    val Sewerage = Taupe
    val SewerageTint = TaupeTint

    val Odometer = Color(0xFF1C1E22)
    val OdometerPrevious = Color(0xFF8A8F96)
    val CameraBackground = Color(0xFF0E0F11)
}

/** Meter numbers and readings: monospaced so digits line up like the meter's wheels. */
val NumberFont: FontFamily = FontFamily.Monospace

// Larger than Material defaults: readers hold the phone at arm's length, often in sun.
private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
)

@Composable
fun MeterReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = AppColors.Navy,
            onPrimary = Color.White,
            secondary = AppColors.Taupe,
            onSecondary = Color.White,
            background = AppColors.Background,
            onBackground = AppColors.Ink,
            surface = AppColors.Card,
            onSurface = AppColors.Ink,
            error = AppColors.Bad,
            outline = AppColors.Line,
        ),
        typography = AppTypography,
        content = content,
    )
}
