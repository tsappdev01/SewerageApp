package com.meterreading.reader.ui.components

import com.meterreading.reader.platform.*
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meterreading.reader.resources.*
import com.meterreading.reader.data.MeterType
import com.meterreading.reader.data.ReadingState
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.util.LocalSpeaker

enum class BigButtonKind { Primary, Success, Warning, Secondary }

/** The main way to act. One Primary per screen: it is always the next step. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: BigButtonKind = BigButtonKind.Primary,
    subtitle: String? = null,
    enabled: Boolean = true,
    minHeight: Dp = 68.dp,
    contentColorOverride: Color? = null,
) {
    val haptics = LocalHapticFeedback.current
    val (container, content) = when (kind) {
        BigButtonKind.Primary -> AppColors.Navy to Color.White
        BigButtonKind.Success -> AppColors.Ok to Color.White
        BigButtonKind.Warning -> AppColors.Warn to Color.White
        BigButtonKind.Secondary -> AppColors.Card to AppColors.Ink
    }
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (enabled) container else AppColors.Line,
        contentColor = if (enabled) contentColorOverride ?: content else AppColors.SubInk,
        border = if (kind == BigButtonKind.Secondary) BorderStroke(2.dp, AppColors.Line) else null,
        shadowElevation = if (kind == BigButtonKind.Primary && enabled) 3.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = minHeight)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    container: Color = AppColors.Card,
    content: Color = AppColors.Ink,
    bordered: Boolean = true,
    size: Dp = 52.dp,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = if (bordered) BorderStroke(1.5.dp, AppColors.Line) else null,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/** Reads [text] aloud. On every screen, top right. */
@Composable
fun SpeakButton(text: String) {
    val speaker = LocalSpeaker.current
    val missing = stringResource(Res.string.voice_missing)
    CircleIconButton(
        icon = Icons.AutoMirrored.Rounded.VolumeUp,
        description = stringResource(Res.string.read_aloud),
        onClick = { if (speaker?.speak(text) != true) Messages.show(missing) },
        container = AppColors.NavyTint,
        content = AppColors.Navy,
        bordered = false,
    )
}

@Composable
fun AppTopBar(title: String, speakText: String, onBack: (() -> Unit)? = null, onSettings: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.back), onBack)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (onSettings != null) SettingsButton(onSettings)
        SpeakButton(speakText)
    }
}

/** Gear icon that opens Settings (server address, company sign-in). */
@Composable
fun SettingsButton(onClick: () -> Unit) {
    CircleIconButton(Icons.Rounded.Settings, stringResource(Res.string.settings_title), onClick)
}

/** Rounded label with an icon. State is never shown as colour or text alone. */
@Composable
fun Pill(text: String?, foreground: Color, background: Color, icon: ImageVector? = null) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
        if (text != null) Text(text, color = foreground, style = MaterialTheme.typography.labelMedium)
    }
}

data class StateStyle(val icon: ImageVector, val label: StringResource, val foreground: Color, val background: Color)

fun stateStyle(state: ReadingState): StateStyle = when (state) {
    ReadingState.PENDING -> StateStyle(Icons.Rounded.RadioButtonUnchecked, Res.string.state_pending, AppColors.SubInk, AppColors.Background)
    ReadingState.QUEUED -> StateStyle(Icons.Rounded.CloudUpload, Res.string.state_queued, AppColors.Queued, AppColors.QueuedTint)
    ReadingState.SENT -> StateStyle(Icons.Rounded.CheckCircle, Res.string.state_sent, AppColors.Ok, AppColors.OkTint)
    ReadingState.CHECKING -> StateStyle(Icons.Rounded.Warning, Res.string.state_checking, AppColors.Warn, AppColors.WarnTint)
    ReadingState.READ_AGAIN -> StateStyle(Icons.Rounded.Close, Res.string.state_read_again, AppColors.Bad, AppColors.BadTint)
    ReadingState.REVISIT -> StateStyle(Icons.Rounded.Refresh, Res.string.state_revisit, AppColors.Warn, AppColors.WarnTint)
}

@Composable
fun StateChip(state: ReadingState, showLabel: Boolean = true) {
    val style = stateStyle(state)
    Pill(if (showLabel) stringResource(style.label) else null, style.foreground, style.background, style.icon)
}

fun meterTypeIcon(type: MeterType): ImageVector = when (type) {
    MeterType.IRRIGATION -> Icons.Rounded.WaterDrop
    MeterType.SEWERAGE -> Icons.Rounded.Plumbing
}

fun meterTypeLabel(type: MeterType): StringResource = when (type) {
    MeterType.IRRIGATION -> Res.string.irrigation
    MeterType.SEWERAGE -> Res.string.sewerage
}

/** Green drop for irrigation, taupe pipe for sewerage, everywhere in the app. */
@Composable
fun MeterTypeIcon(type: MeterType, size: Dp = 48.dp, outlined: Boolean = false) {
    val (fg, bg) = when (type) {
        MeterType.IRRIGATION -> AppColors.Irrigation to AppColors.IrrigationTint
        MeterType.SEWERAGE -> AppColors.Sewerage to AppColors.SewerageTint
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(bg)
            .then(if (outlined) Modifier.border(2.dp, AppColors.Ok, RoundedCornerShape(size / 4)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(meterTypeIcon(type), contentDescription = stringResource(meterTypeLabel(type)), tint = fg, modifier = Modifier.size(size * 0.6f))
    }
}

/** Large picture choice: meter condition and reasons. */
@Composable
fun PictureTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier.heightIn(min = 112.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) AppColors.OkTint else AppColors.Card,
        border = BorderStroke(if (selected) 3.dp else 2.dp, if (selected) AppColors.Ok else AppColors.Line),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp)
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Icon(icon, contentDescription = null, tint = if (selected) AppColors.Ok else tint, modifier = Modifier.size(44.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = AppColors.Ink)
            }
            if (selected) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = AppColors.Ok,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp),
                )
            }
        }
    }
}

/** Two-column grid of tiles that sits inside a scrolling column. */
@Composable
fun <T> TileGrid(items: List<T>, tile: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile(it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ProgressRing(done: Int, total: Int, size: Dp = 104.dp) {
    val fraction = if (total == 0) 0f else done.toFloat() / total
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke.width, this.size.height - stroke.width)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(AppColors.Line, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            drawArc(AppColors.Ok, -90f, 360f * fraction, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        }
        Icon(Icons.Rounded.Check, contentDescription = null, tint = AppColors.Ok, modifier = Modifier.size(size * 0.45f))
    }
}

@Composable
fun ProgressBar(done: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction = if (total == 0) 0f else done.toFloat() / total
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(AppColors.Line),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(AppColors.Ok),
        )
    }
}

@Composable
fun Banner(text: String, icon: ImageVector, foreground: Color, background: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(36.dp))
        Text(text, color = foreground, style = MaterialTheme.typography.titleMedium)
    }
}
