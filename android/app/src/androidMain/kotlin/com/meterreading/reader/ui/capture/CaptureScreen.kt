package com.meterreading.reader.ui.capture

import android.media.AudioManager
import android.media.ToneGenerator
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meterreading.reader.R
import com.meterreading.reader.data.*
import com.meterreading.reader.ui.components.*
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.NumberFont
import com.meterreading.reader.util.findActivity
import com.meterreading.reader.util.formatReading
import com.meterreading.reader.util.registerDigits
import com.meterreading.reader.util.rememberVoiceInput
import kotlinx.coroutines.delay
import java.io.File

/**
 * One meter, start to finish: condition, then the steps StatusRules gives for it, then the
 * server's answer and the next meter. System back goes one step back.
 */
@Composable
fun CaptureScreen(meterId: String, onNextMeter: (String) -> Unit, onHome: () -> Unit, onExit: () -> Unit) {
    val vm: CaptureViewModel = viewModel(key = "capture-$meterId") { CaptureViewModel(AppGraph.repository, meterId) }
    SecureWindow()
    BackHandler { if (!vm.back()) onExit() }

    val outcome = vm.outcome
    when {
        outcome != null -> ResultStep(vm, outcome, onNextMeter, onHome)
        vm.stepIndex < 0 -> ConditionStep(vm, onBack = onExit)
        else -> when (val step = vm.currentStep!!) {
            is Step.Reason -> ReasonStep(vm, step.category)
            is Step.Photo -> PhotoStep(vm, step.role)
            is Step.Number -> NumberStep(vm, step.target, step.required)
            is Step.Note -> NoteStep(vm, step.required)
            Step.NewMeterNumber -> NewMeterNumberStep(vm)
            Step.Confirm -> ConfirmStep(vm)
        }
    }
}

/** SEC-007: no screenshots or recent-apps previews of meter evidence. */
@Composable
private fun SecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}


/** Top bar, step circles and meter card, then the step's own content and bottom buttons. */
@Composable
private fun StepScaffold(
    vm: CaptureViewModel,
    title: String,
    speakText: String,
    onBack: () -> Unit,
    showMeter: Boolean = true,
    scroll: Boolean = true,
    bottom: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title, speakText, onBack)
        StepIndicator(
            icons = listOf(Choices.stepIcon(null)) + vm.steps.map { Choices.stepIcon(it) },
            current = vm.stepIndex + 1,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showMeter) MeterContext(vm.meter, vm.property)
            content()
        }
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = bottom,
        )
    }
}

@Composable
private fun MeterContext(meter: Meter, property: Property) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.Card)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MeterTypeIcon(meter.type, size = 44.dp)
            Column {
                Text(meter.number, fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(property.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${meter.zoneCode} · ${property.code}", color = AppColors.SubInk)
            }
        }
        meter.supervisorNote?.let {
            Banner(stringResource(R.string.supervisor_note, it), Icons.Rounded.Close, AppColors.Bad, AppColors.BadTint)
        }
    }
}

@Composable
private fun ConditionStep(vm: CaptureViewModel, onBack: () -> Unit) {
    val nextIsPhoto = vm.steps.firstOrNull() is Step.Photo
    StepScaffold(
        vm, stringResource(R.string.q_condition), stringResource(R.string.speak_condition), onBack,
        bottom = {
            BigButton(
                stringResource(if (nextIsPhoto) R.string.next_photo else R.string.next),
                vm::next,
                icon = if (nextIsPhoto) Icons.Rounded.PhotoCamera else Icons.AutoMirrored.Rounded.ArrowForward,
            )
        },
    ) {
        TileGrid(Choices.conditions.entries.toList()) { (condition, choice), modifier ->
            PictureTile(choice.icon, stringResource(choice.label), choice.tint, vm.condition == condition, { vm.chooseCondition(condition) }, modifier)
        }
    }
}

@Composable
private fun ReasonStep(vm: CaptureViewModel, category: ReasonCategory) {
    StepScaffold(
        vm, stringResource(Choices.reasonQuestion(category)), stringResource(R.string.speak_reason), { vm.back() },
        bottom = { BigButton(stringResource(R.string.next), vm::next, icon = Icons.AutoMirrored.Rounded.ArrowForward, enabled = vm.reasonCode != null) },
    ) {
        TileGrid(Choices.reasons(category)) { choice, modifier ->
            PictureTile(choice.icon, stringResource(choice.label), choice.tint, vm.reasonCode == choice.code, { vm.reasonCode = choice.code }, modifier)
        }
    }
}

@Composable
private fun PhotoStep(vm: CaptureViewModel, role: ImageRole) {
    val file = vm.photos[role]
    if (file == null) {
        CameraCapture(
            hint = stringResource(Choices.photoHint(role)),
            speakText = stringResource(if (role == ImageRole.DISPLAY || role == ImageRole.OLD_METER_FINAL) R.string.speak_photo else R.string.speak_photo_context),
            showFrame = role == ImageRole.DISPLAY || role == ImageRole.OLD_METER_FINAL,
            onCaptured = { vm.photos[role] = it },
        )
        return
    }
    StepScaffold(
        vm, stringResource(R.string.q_photo_clear), stringResource(R.string.speak_photo_review), { vm.back() }, showMeter = false,
        bottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(
                    stringResource(R.string.photo_again), { vm.photos.remove(role) }, Modifier.weight(1f),
                    icon = Icons.Rounded.PhotoCamera, kind = BigButtonKind.Secondary,
                )
                BigButton(stringResource(R.string.photo_good), vm::next, Modifier.weight(1f), icon = Icons.Rounded.Check, kind = BigButtonKind.Success)
            }
        },
    ) {
        ZoomablePhoto(file, height = 420.dp)
    }
}

/** Pinch to zoom, so the reader can read small wheels from the photo. */
@Composable
private fun ZoomablePhoto(file: File?, height: Dp) {
    val photo = rememberPhoto(file)
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF2A2D31))
            .transformable(state),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(
                photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun NumberStep(vm: CaptureViewModel, target: NumberTarget, required: Boolean) {
    var warning by remember(target) { mutableStateOf<ReadingRules.Check?>(null) }
    val digits = vm.digits(target)
    val previous = vm.previousFor(target)
    val size = vm.meter.registerDigits

    val shown = warning
    if (shown != null) {
        StepScaffold(
            vm, stringResource(R.string.type_again), stringResource(R.string.speak_warning), { warning = null }, showMeter = false,
            bottom = {
                BigButton(stringResource(R.string.type_again), {
                    vm.clearDigits(target)
                    warning = null
                }, icon = Icons.Rounded.Pin)
                BigButton(stringResource(R.string.it_is_correct), {
                    vm.readerConfirmedWarning = true
                    warning = null
                    vm.next()
                }, kind = BigButtonKind.Secondary, minHeight = 56.dp)
            },
        ) {
            Banner(
                stringResource(if (shown is ReadingRules.Check.Lower) R.string.warn_lower else R.string.warn_high),
                Icons.Rounded.Warning, AppColors.Warn, AppColors.WarnTint,
            )
            ZoomablePhoto(vm.photoFor(target), height = 130.dp)
            if (previous != null) {
                Label(stringResource(R.string.last_time))
                Odometer(registerDigits(previous, size), size, style = OdometerStyle.Previous)
            }
            Label(stringResource(R.string.you_typed))
            Odometer(digits, size)
        }
        return
    }

    StepScaffold(
        vm, stringResource(Choices.numberTitle(target)), stringResource(R.string.speak_number), { vm.back() }, showMeter = false, scroll = false,
        bottom = {
            if (!required) {
                BigButton(stringResource(R.string.cant_read), { vm.skipNumber(target) }, kind = BigButtonKind.Secondary, minHeight = 52.dp)
            }
            NumberPad(
                onDigit = { vm.typeDigit(target, it) },
                onDelete = { vm.deleteDigit(target) },
                onDone = {
                    val check = vm.checkFor(target)
                    if ((check is ReadingRules.Check.Lower || check is ReadingRules.Check.High) && !vm.readerConfirmedWarning) {
                        warning = check
                    } else {
                        vm.next()
                    }
                },
                doneEnabled = vm.isComplete(target),
            )
        },
    ) {
        ZoomablePhoto(vm.photoFor(target), height = 110.dp)
        if (previous != null) {
            Label(stringResource(R.string.last_time))
            Odometer(registerDigits(previous, size), size, style = OdometerStyle.Previous)
        }
        Label(stringResource(R.string.now), strong = true)
        Odometer(digits, size, showCursor = true)
        vm.checkFor(target)?.consumption?.takeIf { target != NumberTarget.OLD_FINAL }?.let {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Pill(stringResource(R.string.used, formatReading(it)), AppColors.Ok, AppColors.OkTint)
            }
        }
    }
}

@Composable
private fun Label(text: String, strong: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (strong) AppColors.Ink else AppColors.SubInk,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NoteStep(vm: CaptureViewModel, required: Boolean) {
    var typing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val voiceMissing = stringResource(R.string.search_voice_missing)
    val voice = rememberVoiceInput(stringResource(R.string.note_title)) { spoken ->
        vm.note = if (vm.note.isBlank()) spoken else vm.note.trimEnd() + " " + spoken
    }
    StepScaffold(
        vm, stringResource(R.string.note_title), stringResource(R.string.speak_note), { vm.back() },
        bottom = { BigButton(stringResource(R.string.next), vm::next, icon = Icons.AutoMirrored.Rounded.ArrowForward, enabled = !required || vm.note.isNotBlank()) },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                onClick = {
                    if (!voice()) {
                        typing = true
                        Toast.makeText(context, voiceMissing, Toast.LENGTH_LONG).show()
                    }
                },
                shape = CircleShape,
                color = AppColors.Navy,
                contentColor = Color.White,
                modifier = Modifier.size(132.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Mic, stringResource(R.string.note_speak), Modifier.size(64.dp)) }
            }
            Text(stringResource(R.string.note_speak), style = MaterialTheme.typography.titleMedium)
        }
        if (typing || vm.note.isNotBlank()) {
            OutlinedTextField(
                value = vm.note,
                onValueChange = { vm.note = it.take(500) },
                label = { Text(stringResource(R.string.note_hint)) },
                textStyle = MaterialTheme.typography.bodyLarge,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            TextButton(onClick = { typing = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Keyboard, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.note_type), style = MaterialTheme.typography.titleMedium)
            }
        }
        if (required && vm.note.isBlank()) {
            Text(stringResource(R.string.note_required), color = AppColors.SubInk, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun NewMeterNumberStep(vm: CaptureViewModel) {
    StepScaffold(
        vm, stringResource(R.string.new_meter_number_title), stringResource(R.string.speak_new_meter), { vm.back() },
        bottom = { BigButton(stringResource(R.string.next), vm::next, icon = Icons.AutoMirrored.Rounded.ArrowForward, enabled = vm.newMeterNumber.isNotBlank()) },
    ) {
        ZoomablePhoto(vm.photos[ImageRole.NEW_METER], height = 160.dp)
        OutlinedTextField(
            value = vm.newMeterNumber,
            onValueChange = { vm.newMeterNumber = it.uppercase().take(30) },
            label = { Text(stringResource(R.string.new_meter_number_hint)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = NumberFont),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConfirmStep(vm: CaptureViewModel) {
    val choice = Choices.conditions.getValue(vm.condition)
    StepScaffold(
        vm, stringResource(R.string.check_title), stringResource(R.string.speak_check), { vm.back() },
        bottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(
                    stringResource(R.string.change), { vm.back() }, Modifier.weight(1f),
                    icon = Icons.Rounded.Close, kind = BigButtonKind.Secondary, contentColorOverride = AppColors.Bad, enabled = !vm.sending,
                )
                BigButton(
                    stringResource(if (vm.sending) R.string.sending else R.string.yes_send), vm::submit, Modifier.weight(1f),
                    icon = Icons.Rounded.Check, kind = BigButtonKind.Success, enabled = !vm.sending && vm.tenantProblem == null,
                )
            }
        },
    ) {
        val photo = vm.photos[ImageRole.DISPLAY] ?: vm.photos[ImageRole.OLD_METER_FINAL] ?: vm.photos.values.firstOrNull()
        ZoomablePhoto(photo, height = 170.dp)
        Text(stringResource(R.string.q_same), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (vm.condition == MeterCondition.METER_REPLACED) {
            BigValue(stringResource(R.string.old_meter), vm.value(NumberTarget.OLD_FINAL))
            BigValue(stringResource(R.string.new_meter) + " " + vm.newMeterNumber, vm.value(NumberTarget.NEW_CURRENT))
        } else if (vm.condition != MeterCondition.NOT_ACCESSIBLE) {
            BigValue(null, vm.value(NumberTarget.CURRENT))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            Pill(stringResource(choice.label), choice.tint, AppColors.Card, choice.icon)
            vm.consumption()?.let { Pill(stringResource(R.string.used, formatReading(it)), AppColors.Ok, AppColors.OkTint) }
        }
        if (vm.note.isNotBlank()) Text("“${vm.note}”", color = AppColors.SubInk, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        TenantCheck(vm)
        SubTenantField(vm)
    }
}

/**
 * FR-006.12: the reader taps the tenant they see on site. Nothing is picked for them, even when there is
 * only one, and "Yes, send" stays off until they do. A property with no tenant cannot be saved.
 */
@Composable
private fun TenantCheck(vm: CaptureViewModel) {
    val tenants = vm.property.tenants
    Text(stringResource(R.string.tenant_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
    if (tenants.isEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(AppColors.BadTint, RoundedCornerShape(12.dp)).padding(14.dp),
        ) {
            Icon(Icons.Rounded.Warning, null, tint = AppColors.Bad, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.tenant_none), style = MaterialTheme.typography.titleMedium, color = AppColors.Ink)
        }
        return
    }
    Text(
        stringResource(if (tenants.size == 1) R.string.tenant_q_one else R.string.tenant_q_many),
        style = MaterialTheme.typography.bodyLarge, color = AppColors.SubInk, modifier = Modifier.fillMaxWidth(),
    )
    tenants.forEach { t ->
        val chosen = vm.tenantCode == t.code
        val shape = RoundedCornerShape(12.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(shape)
                .background(if (chosen) AppColors.NavyTint else AppColors.Card)
                .border(if (chosen) 3.dp else 1.dp, if (chosen) AppColors.Navy else AppColors.Line, shape)
                .selectable(selected = chosen, enabled = !vm.sending, role = Role.RadioButton) { vm.tenantCode = t.code }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(
                if (chosen) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                tint = if (chosen) AppColors.Navy else AppColors.SubInk, modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.displayName, style = MaterialTheme.typography.titleMedium, color = AppColors.Ink)
                Text(t.code, style = MaterialTheme.typography.bodyMedium, color = AppColors.SubInk)
            }
            if (chosen) Text(stringResource(R.string.tenant_checked), style = MaterialTheme.typography.labelLarge, color = AppColors.Navy)
        }
    }
    if (vm.tenantCode == null) {
        Text(stringResource(R.string.tenant_needed), style = MaterialTheme.typography.bodyMedium, color = AppColors.SubInk, modifier = Modifier.fillMaxWidth())
    }
}

/** Optional sub-tenant name. Hidden behind a button so it never slows down a normal reading. */
@Composable
private fun SubTenantField(vm: CaptureViewModel) {
    var open by remember { mutableStateOf(vm.subTenant.isNotBlank()) }
    if (!open) {
        TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), enabled = !vm.sending) {
            Icon(Icons.Rounded.PersonAdd, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sub_tenant_add), style = MaterialTheme.typography.titleMedium)
        }
    } else {
        OutlinedTextField(
            value = vm.subTenant,
            onValueChange = { vm.subTenant = it.take(100) },
            label = { Text(stringResource(R.string.sub_tenant_label)) },
            singleLine = true,
            enabled = !vm.sending,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BigValue(label: String?, value: Long?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = AppColors.SubInk)
        Text(
            value?.let(::formatReading) ?: stringResource(R.string.no_number),
            fontFamily = NumberFont,
            fontWeight = FontWeight.Bold,
            fontSize = if (value != null) 48.sp else 28.sp,
            color = if (value != null) AppColors.Ink else AppColors.SubInk,
        )
    }
}

@Composable
private fun ResultStep(vm: CaptureViewModel, outcome: SubmitOutcome, onNextMeter: (String) -> Unit, onHome: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(outcome) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(if (outcome == SubmitOutcome.SENT) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, 250)
        delay(400)
        tone.release()
    }
    val meters by AppGraph.repository.meters.collectAsState()
    val done = meters.count { !it.state.canCapture }
    data class Look(val icon: androidx.compose.ui.graphics.vector.ImageVector, val fg: Color, val bg: Color, val title: Int, val speak: Int)
    val look = when (outcome) {
        SubmitOutcome.SENT -> Look(Icons.Rounded.Check, AppColors.Ok, AppColors.OkTint, R.string.result_sent, R.string.speak_result_sent)
        SubmitOutcome.QUEUED -> Look(Icons.Rounded.CloudUpload, AppColors.Queued, AppColors.QueuedTint, R.string.result_saved, R.string.speak_result_saved)
        SubmitOutcome.CHECKING -> Look(Icons.Rounded.Warning, AppColors.Warn, AppColors.WarnTint, R.string.result_checking, R.string.speak_result_checking)
        SubmitOutcome.REJECTED -> Look(Icons.Rounded.Close, AppColors.Bad, AppColors.BadTint, R.string.result_rejected, R.string.speak_result_rejected)
    }
    val subtitle = when (outcome) {
        SubmitOutcome.SENT -> stringResource(R.string.result_sent_sub, vm.meter.number)
        SubmitOutcome.QUEUED -> stringResource(R.string.result_saved_sub)
        SubmitOutcome.CHECKING -> stringResource(R.string.result_checking_sub)
        SubmitOutcome.REJECTED -> vm.refusal ?: stringResource(R.string.result_rejected_sub)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) { SpeakButton(stringResource(look.speak)) }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Box(
                Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(look.bg),
                contentAlignment = Alignment.Center,
            ) { Icon(look.icon, null, tint = look.fg, modifier = Modifier.size(80.dp)) }
            Text(stringResource(look.title), style = MaterialTheme.typography.headlineLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = AppColors.SubInk, textAlign = TextAlign.Center)
            Pill(stringResource(R.string.progress_count, done, meters.size), AppColors.Ok, AppColors.OkTint)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val next = vm.nextMeter
            if (next != null) {
                BigButton(
                    stringResource(R.string.next_meter), { onNextMeter(next.id) },
                    icon = Icons.Rounded.PlayArrow,
                    subtitle = "${next.number} · ${stringResource(meterTypeLabel(next.type))}",
                    minHeight = 92.dp,
                )
            }
            BigButton(stringResource(R.string.home), onHome, kind = BigButtonKind.Secondary, minHeight = 56.dp, icon = Icons.Rounded.Home)
        }
    }
}
