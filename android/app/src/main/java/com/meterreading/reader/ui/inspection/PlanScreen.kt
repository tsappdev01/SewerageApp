package com.meterreading.reader.ui.inspection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meterreading.reader.R
import com.meterreading.reader.api.InspectionPlanDto
import com.meterreading.reader.data.AppGraph
import com.meterreading.reader.data.InspectionRules
import com.meterreading.reader.data.InspectionState
import com.meterreading.reader.data.PlanTab
import com.meterreading.reader.ui.components.*
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.NumberFont
import com.meterreading.reader.util.formatDay
import com.meterreading.reader.util.formatDayTime
import java.time.LocalDate

/** Spec FR-031: the plan rows to inspect, by Today / Late / Week / Done, with each one's progress. */
@Composable
fun InspectionPlanScreen(onPlan: (String) -> Unit, onBack: () -> Unit) {
    val repo = AppGraph.inspections ?: return
    val plans by repo.plans.collectAsStateWithLifecycle()
    val today by repo.today.collectAsStateWithLifecycle()
    val savedFrom by repo.savedFrom.collectAsStateWithLifecycle()
    val drafts by repo.drafts.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(PlanTab.TODAY) }
    var text by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        repo.refreshPlan()
        loading = false
    }
    // Open on Late when nothing is planned for today but some are late.
    LaunchedEffect(plans) {
        if (tab == PlanTab.TODAY && InspectionRules.plansFor(PlanTab.TODAY, plans, today).isEmpty() &&
            InspectionRules.plansFor(PlanTab.LATE, plans, today).isNotEmpty()
        ) tab = PlanTab.LATE
    }
    val shown = remember(plans, tab, text, today) { InspectionRules.plansFor(tab, plans, today, text) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.insp_plan_title), stringResource(R.string.speak_insp_plan), onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            savedFrom?.let { Pill(stringResource(R.string.insp_saved_plan, formatDayTime(it)), AppColors.Warn, AppColors.WarnTint, Icons.Rounded.CloudOff) }
            Tabs(tab, { tab = it }) { t -> InspectionRules.plansFor(t, plans, today).size }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(30) },
                placeholder = { Text(stringResource(R.string.insp_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            loading && plans.isEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            shown.isEmpty() -> Text(
                stringResource(R.string.insp_none), style = MaterialTheme.typography.titleMedium, color = AppColors.SubInk,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = { it.id }) { p -> PlanCard(p, today, inProgress = drafts.containsKey(p.id), onClick = { onPlan(p.id) }) }
            }
        }
    }
}

/** Segmented tabs with counts; the chosen one is white on grey, with its count. */
@Composable
private fun Tabs(tab: PlanTab, onTab: (PlanTab) -> Unit, count: (PlanTab) -> Int) {
    val labels = mapOf(
        PlanTab.TODAY to R.string.insp_tab_today, PlanTab.LATE to R.string.insp_tab_late,
        PlanTab.WEEK to R.string.insp_tab_week, PlanTab.DONE to R.string.insp_tab_done,
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AppColors.Line).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        PlanTab.entries.forEach { t ->
            val on = t == tab
            Surface(
                onClick = { onTab(t) },
                shape = RoundedCornerShape(11.dp),
                color = if (on) AppColors.Card else AppColors.Line,
                shadowElevation = if (on) 1.dp else 0.dp,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(4.dp)) {
                    Text(stringResource(labels.getValue(t)), style = MaterialTheme.typography.labelMedium, color = if (on) AppColors.Ink else AppColors.SubInk)
                    val n = count(t)
                    Text("$n", fontFamily = NumberFont, fontSize = 13.sp, color = if (t == PlanTab.LATE && n > 0) AppColors.Bad else AppColors.SubInk)
                }
            }
        }
    }
}

@Composable
private fun PlanCard(p: InspectionPlanDto, today: LocalDate, inProgress: Boolean, onClick: () -> Unit) {
    val state = runCatching { InspectionState.valueOf(p.state) }.getOrDefault(InspectionState.NOT_STARTED)
    val date = runCatching { LocalDate.parse(p.planDate.take(10)) }.getOrNull()
    val late = state != InspectionState.DONE && date != null && date.isBefore(today)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = AppColors.Card,
        border = BorderStroke(if (inProgress) 3.dp else 1.5.dp, if (inProgress) AppColors.Navy else AppColors.Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UnitRing(p.checkedUnits, maxOf(p.activeUnits, p.checkedUnits))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(p.propertyCode, fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(p.companyName ?: p.tenantCode, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (late && date != null) {
                        Icon(Icons.Rounded.EventBusy, null, tint = AppColors.Bad, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.insp_late_since, formatDay(date)), color = AppColors.Bad, style = MaterialTheme.typography.labelMedium)
                    } else if (date != null) {
                        Icon(Icons.Rounded.Event, null, tint = AppColors.SubInk, modifier = Modifier.size(18.dp))
                        Text(formatDay(date), color = AppColors.SubInk)
                    }
                    Text(stringResource(R.string.insp_units_active_inactive, p.activeUnits, p.inactiveUnits), color = AppColors.SubInk, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (inProgress) Pill(stringResource(R.string.insp_in_progress), AppColors.Navy, AppColors.NavyTint, Icons.Rounded.EditNote)
                    else if (state != InspectionState.NOT_STARTED) StatePill(state)
                    if (p.flaggedUnits > 0) Pill(stringResource(R.string.insp_flagged_count, p.flaggedUnits), AppColors.Sublet, AppColors.SubletTint, Icons.Rounded.Flag)
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AppColors.SubInk)
        }
    }
}

/** Small progress ring: units checked of active units, with the count inside. */
@Composable
fun UnitRing(done: Int, total: Int, size: Dp = 56.dp) {
    val fraction = if (total == 0) 0f else done.toFloat() / total
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arc = androidx.compose.ui.geometry.Size(this.size.width - stroke.width, this.size.height - stroke.width)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(AppColors.Line, 0f, 360f, useCenter = false, topLeft = topLeft, size = arc, style = stroke)
            drawArc(AppColors.Ok, -90f, 360f * fraction, useCenter = false, topLeft = topLeft, size = arc, style = stroke)
        }
        Text("$done/$total", fontFamily = NumberFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
