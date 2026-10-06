package com.meterreading.reader.ui.inspection

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meterreading.reader.R
import com.meterreading.reader.api.InspectionUnitsDto
import com.meterreading.reader.data.AppGraph
import com.meterreading.reader.data.InspectionState
import com.meterreading.reader.data.UnitResult
import com.meterreading.reader.ui.components.*
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.NumberFont
import com.meterreading.reader.util.currentLocation
import com.meterreading.reader.util.formatDay
import com.meterreading.reader.util.hasLocationPermission
import java.time.LocalDate

/**
 * Spec FR-031: one plan row before the units. Starting the visit records where the phone is, kept with
 * the visit as proof it took place. No location is a warning, never a block (GPS is weak in big sheds).
 */
@Composable
fun InspectionPropertyScreen(planId: String, onStart: () -> Unit, onBack: () -> Unit) {
    val repo = AppGraph.inspections ?: return
    val context = LocalContext.current
    val plans by repo.plans.collectAsStateWithLifecycle()
    val drafts by repo.drafts.collectAsStateWithLifecycle()
    val plan = plans.firstOrNull { it.id == planId } ?: return
    val draft = drafts[planId]
    var units by remember { mutableStateOf(repo.cachedUnits(planId)) }
    var loadFailed by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(planId) {
        val loaded = repo.units(plan)
        if (loaded != null) units = loaded else loadFailed = units == null
    }

    fun locate() {
        locating = true
        currentLocation(context) { fix ->
            locating = false
            if (fix != null) repo.update(planId) { it.copy(latitude = fix.latitude, longitude = fix.longitude, gpsAccuracyM = fix.accuracyM) }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locate() }

    fun start() {
        repo.startVisit(plan)
        if (hasLocationPermission(context)) locate()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        onStart()
    }

    val date = runCatching { LocalDate.parse(plan.planDate.take(10)) }.getOrNull()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(plan.propertyCode, stringResource(R.string.speak_insp_property), onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AppColors.Card).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(AppColors.TaupeTint), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Apartment, null, tint = AppColors.Taupe, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(plan.companyName ?: plan.tenantCode, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.insp_tenant_line, plan.tenantCode, date?.let(::formatDay) ?: plan.planDate), color = AppColors.SubInk)
                }
            }
            if (draft != null) LocationLine(draft.latitude != null, draft.gpsAccuracyM, locating, onRetry = { if (hasLocationPermission(context)) locate() else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) })

            Text(stringResource(R.string.insp_units_on_record), style = MaterialTheme.typography.labelMedium, color = AppColors.SubInk)
            UnitBar(plan.activeUnits, plan.inactiveUnits)
            when {
                units != null -> UnitsSummary(units!!, plan.state)
                loadFailed -> Banner(stringResource(R.string.insp_load_failed), Icons.Rounded.CloudOff, AppColors.Warn, AppColors.WarnTint)
                else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(
                stringResource(if (draft == null) R.string.insp_start else R.string.insp_continue),
                { if (draft == null) start() else onStart() },
                icon = Icons.Rounded.FactCheck,
                enabled = units != null,
            )
            if (draft != null) {
                TextButton(onClick = { askDiscard = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.DeleteOutline, null, tint = AppColors.Bad)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.insp_discard), color = AppColors.Bad, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.insp_discard_q)) },
            confirmButton = { TextButton(onClick = { repo.discard(planId); askDiscard = false }) { Text(stringResource(R.string.insp_discard_yes), color = AppColors.Bad) } },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.insp_discard_no)) } },
        )
    }
}

@Composable
private fun LocationLine(saved: Boolean, accuracy: Double?, locating: Boolean, onRetry: () -> Unit) {
    val (fg, bg) = if (saved) AppColors.Ok to AppColors.OkTint else AppColors.Warn to AppColors.WarnTint
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (saved) Icons.Rounded.LocationOn else Icons.Rounded.LocationOff, null, tint = fg, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    when {
                        saved -> R.string.insp_location_ok
                        locating -> R.string.insp_location_wait
                        else -> R.string.insp_location_off
                    },
                ),
                color = fg, style = MaterialTheme.typography.titleMedium,
            )
            if (saved && accuracy != null) Text(stringResource(R.string.insp_location_acc, accuracy.toInt()), color = AppColors.SubInk)
        }
        if (!saved && !locating) TextButton(onClick = onRetry) { Text(stringResource(R.string.insp_location_retry)) }
    }
}

/** Active and inactive units on record as one bar with its key in words. */
@Composable
fun UnitBar(active: Int, inactive: Int) {
    val total = (active + inactive).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(AppColors.Line)) {
        if (active > 0) Box(Modifier.weight(active.toFloat() / total).fillMaxHeight().background(AppColors.Navy))
        if (inactive > 0) Box(Modifier.weight(inactive.toFloat() / total).fillMaxHeight().background(AppColors.Line))
    }
    Text(stringResource(R.string.insp_units_active_inactive, active, inactive), color = AppColors.SubInk)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitsSummary(units: InspectionUnitsDto, planState: String) {
    val subTenants = units.units.mapNotNull { it.subTenantName?.takeIf { s -> s.isNotBlank() } }.groupingBy { it }.eachCount()
    if (subTenants.isNotEmpty()) {
        Text(stringResource(R.string.insp_subtenants), style = MaterialTheme.typography.labelMedium, color = AppColors.SubInk)
        subTenants.entries.take(6).forEach { (name, count) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.Card).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.Groups, null, tint = AppColors.SubInk)
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.insp_units_count, count), color = AppColors.SubInk)
            }
        }
    }
    // What earlier visits found, so the inspector knows what to look at again.
    val last = units.units.mapNotNull { u -> u.last?.let { runCatching { UnitResult.valueOf(it.result) }.getOrNull() } }.groupingBy { it }.eachCount()
    if (last.isNotEmpty()) {
        Text(stringResource(R.string.insp_last_inspection), style = MaterialTheme.typography.labelMedium, color = AppColors.SubInk)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            last.forEach { (r, n) ->
                val s = resultStyle(r)
                Pill("$n · ${stringResource(s.label)}", s.fg, s.bg, s.icon)
            }
        }
    }
    if (planState == InspectionState.COME_BACK.name) {
        Banner(stringResource(R.string.insp_come_back_banner), Icons.Rounded.Refresh, AppColors.Warn, AppColors.WarnTint)
    }
}
