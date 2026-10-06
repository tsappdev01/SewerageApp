package com.meterreading.reader.ui.inspection

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meterreading.reader.R
import com.meterreading.reader.data.*
import com.meterreading.reader.ui.components.*
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.NumberFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Spec FR-034/036: totals by result, the flagged units, units left to check, the person met and an
 * optional signature, then "Send inspection". Without signal it is saved on the phone and sent later.
 */
@Composable
fun InspectionReviewScreen(planId: String, onNextProperty: (String) -> Unit, onPlanList: () -> Unit, onBack: () -> Unit) {
    val repo = AppGraph.inspections ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plans by repo.plans.collectAsStateWithLifecycle()
    val drafts by repo.drafts.collectAsStateWithLifecycle()
    var result by remember { mutableStateOf<FinishResult?>(null) }
    var sending by remember { mutableStateOf(false) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var padSize by remember { mutableStateOf(IntSize.Zero) }

    result?.let { r ->
        val today = repo.today.value
        val next = remember(plans) {
            (InspectionRules.plansFor(PlanTab.LATE, plans, today) + InspectionRules.plansFor(PlanTab.TODAY, plans, today))
                .firstOrNull { it.id != planId }
        }
        Done(r, plans.firstOrNull { it.id == planId }?.propertyCode.orEmpty(), next?.let { "${it.propertyCode} · ${it.companyName ?: it.tenantCode}" }, { next?.let { onNextProperty(it.id) } }, onPlanList)
        return
    }
    val plan = plans.firstOrNull { it.id == planId }
    val draft = drafts[planId]
    if (plan == null || draft == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val units = remember(planId) { repo.cachedUnits(planId)?.units.orEmpty() }
    val counts = InspectionRules.counts(draft)
    val unchecked = InspectionRules.unchecked(units, draft, plan).size
    val problem = repo.finishProblem(draft)

    fun send() {
        sending = true
        scope.launch {
            // The signature becomes a JPEG next to the photos, sent with the visit's photos.
            if (strokes.isNotEmpty() && padSize.width > 0) {
                val file = File(context.filesDir, "captures/sig-${UUID.randomUUID()}.jpg")
                withContext(Dispatchers.IO) { saveSignature(file, strokes.toList(), padSize) }
                draft.signature?.let { File(it.path).delete() }
                repo.update(planId) { it.copy(signature = EvidencePhoto(UUID.randomUUID().toString(), file.path, Instant.now().toString())) }
            }
            result = repo.finish(planId)
            sending = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.insp_review_title), stringResource(R.string.speak_insp_review), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.insp_review_units, plan.propertyCode, draft.recorded.size), style = MaterialTheme.typography.titleMedium, color = AppColors.SubInk)
            Totals(counts, draft.recorded.sumOf { it.peopleSeen ?: 0 })
            val flagged = draft.recorded.filter { it.result in InspectionRules.flagged }
            if (flagged.isNotEmpty()) {
                Text(stringResource(R.string.insp_flagged_units), style = MaterialTheme.typography.titleMedium)
                flagged.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.Card).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(e.unitCode, fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Column(Modifier.weight(1f)) {
                            Text(e.occupantName.ifBlank { e.note.ifBlank { "—" } }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(stringResource(R.string.insp_photos, e.photos.size), color = AppColors.SubInk)
                        }
                        ResultPill(e.result!!)
                    }
                }
            }
            if (unchecked > 0) Banner(stringResource(R.string.insp_unchecked_warn, unchecked), Icons.Rounded.Refresh, AppColors.Warn, AppColors.WarnTint)
            if (draft.latitude == null) Banner(stringResource(R.string.insp_location_off), Icons.Rounded.LocationOff, AppColors.Warn, AppColors.WarnTint)
            OutlinedTextField(
                value = draft.personMet,
                onValueChange = { v -> repo.update(planId) { it.copy(personMet = v.take(100)) } },
                label = { Text(stringResource(R.string.insp_person_met)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.insp_signature), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (strokes.isNotEmpty()) TextButton(onClick = { strokes.clear() }) { Text(stringResource(R.string.insp_signature_clear)) }
            }
            SignaturePad(strokes, onSize = { padSize = it })
            problem?.let { (e, p) ->
                Banner(
                    (e?.let { stringResource(R.string.insp_unit_title, it.unitCode) + ": " } ?: "") + stringResource(problemLabel(p)),
                    Icons.Rounded.Info, AppColors.Warn, AppColors.WarnTint,
                )
            }
        }
        Box(Modifier.padding(16.dp)) {
            BigButton(
                stringResource(if (sending) R.string.insp_sending else R.string.insp_send), ::send,
                icon = Icons.Rounded.CloudUpload, enabled = !sending && problem == null,
            )
        }
    }
}

/** A ring with one arc per result, and the same counts in words beside it. */
@Composable
private fun Totals(counts: Map<UnitResult, Int>, people: Int) {
    val total = counts.values.sum().coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AppColors.Card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Canvas(Modifier.size(96.dp)) {
            val stroke = Stroke(width = 16.dp.toPx())
            val inset = stroke.width / 2
            val arc = androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width)
            var start = -90f
            UnitResult.entries.forEach { r ->
                val n = counts[r] ?: 0
                if (n > 0) {
                    val sweep = 360f * n / total
                    drawArc(resultStyle(r).fg, start, sweep, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = stroke)
                    start += sweep
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            UnitResult.entries.filter { (counts[it] ?: 0) > 0 }.forEach { r ->
                val s = resultStyle(r)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(s.icon, null, tint = s.fg, modifier = Modifier.size(18.dp))
                    Text(stringResource(s.label), color = s.fg, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text("${counts[r]}", fontFamily = NumberFont, fontWeight = FontWeight.Bold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Groups, null, tint = AppColors.SubInk, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.insp_people_total), color = AppColors.SubInk, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text("$people", fontFamily = NumberFont, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Sign with a finger. Strokes are kept as points, and drawn into a JPEG when the visit is sent. */
@Composable
private fun SignaturePad(strokes: MutableList<List<Offset>>, onSize: (IntSize) -> Unit) {
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    Box(
        Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)
            .border(2.dp, AppColors.Line, RoundedCornerShape(14.dp))
            .onSizeChanged(onSize)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { currentStroke = listOf(it) },
                    onDrag = { change, _ -> currentStroke = currentStroke + change.position },
                    onDragEnd = { if (currentStroke.size > 1) strokes.add(currentStroke); currentStroke = emptyList() },
                    onDragCancel = { currentStroke = emptyList() },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            (strokes + listOf(currentStroke)).filter { it.size > 1 }.forEach { points ->
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, AppColors.Navy, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (strokes.isEmpty() && currentStroke.isEmpty()) {
            Text(stringResource(R.string.insp_sign_here), color = AppColors.SubInk, modifier = Modifier.align(Alignment.Center))
        }
    }
}

private fun saveSignature(file: File, strokes: List<List<Offset>>, size: IntSize) {
    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(0x12, 0x30, 0x5C)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    strokes.forEach { points ->
        val path = android.graphics.Path()
        path.moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { path.lineTo(it.x, it.y) }
        canvas.drawPath(path, paint)
    }
    file.parentFile?.mkdirs()
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
}

@Composable
private fun Done(result: FinishResult, propertyCode: String, next: String?, onNext: () -> Unit, onPlanList: () -> Unit) {
    data class Look(val icon: androidx.compose.ui.graphics.vector.ImageVector, val fg: Color, val bg: Color, val title: Int, val speak: Int)
    val look = when (result.outcome) {
        FinishOutcome.SENT -> Look(Icons.Rounded.Check, AppColors.Ok, AppColors.OkTint, R.string.insp_done_sent, R.string.speak_insp_done_sent)
        FinishOutcome.QUEUED -> Look(Icons.Rounded.CloudUpload, AppColors.Queued, AppColors.QueuedTint, R.string.insp_done_saved, R.string.speak_insp_done_saved)
        FinishOutcome.REJECTED -> Look(Icons.Rounded.Close, AppColors.Bad, AppColors.BadTint, R.string.insp_done_rejected, R.string.speak_insp_done_rejected)
    }
    val subtitle = when {
        result.outcome == FinishOutcome.REJECTED -> result.message.orEmpty()
        result.outcome == FinishOutcome.QUEUED -> stringResource(R.string.insp_done_saved_sub)
        result.state == InspectionState.COME_BACK -> stringResource(R.string.insp_done_come_back_sub, propertyCode)
        else -> stringResource(R.string.insp_done_sent_sub, propertyCode)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) { SpeakButton(stringResource(look.speak)) }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(140.dp).clip(CircleShape).background(look.bg), contentAlignment = Alignment.Center) {
                Icon(look.icon, null, tint = look.fg, modifier = Modifier.size(80.dp))
            }
            Text(stringResource(look.title), style = MaterialTheme.typography.headlineLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = AppColors.SubInk, textAlign = TextAlign.Center)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (next != null && result.outcome != FinishOutcome.REJECTED) {
                BigButton(stringResource(R.string.insp_next_property), onNext, icon = Icons.Rounded.PlayArrow, subtitle = next, minHeight = 92.dp)
                BigButton(stringResource(R.string.insp_back_to_plan), onPlanList, kind = BigButtonKind.Secondary, minHeight = 56.dp, icon = Icons.Rounded.ListAlt)
            } else {
                BigButton(stringResource(R.string.insp_back_to_plan), onPlanList, icon = Icons.Rounded.ListAlt)
            }
        }
    }
}
