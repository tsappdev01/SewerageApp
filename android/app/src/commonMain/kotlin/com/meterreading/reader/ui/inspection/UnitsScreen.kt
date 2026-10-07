package com.meterreading.reader.ui.inspection

import com.meterreading.reader.platform.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meterreading.reader.resources.*
import com.meterreading.reader.api.InspectionPlanDto
import com.meterreading.reader.api.InspectionUnitDto
import com.meterreading.reader.data.*
import com.meterreading.reader.ui.components.*
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.NumberFont
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.meterreading.reader.util.formatDecimal
import com.meterreading.reader.util.formatStamp

private enum class UnitFilter { ALL, TO_CHECK, FLAGGED }

/**
 * Spec FR-032: every unit of the plan row, grouped by building. Tap a unit to record it in a sheet over
 * the list; swipe right for the common case (as recorded). Units recorded on an earlier visit this period
 * show their result. Everything is saved on the phone as it is recorded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectionUnitsScreen(planId: String, inspector: String, onReview: () -> Unit, onBack: () -> Unit) {
    val repo = AppGraph.inspections ?: return
    val plans by repo.plans.collectAsStateWithLifecycle()
    val drafts by repo.drafts.collectAsStateWithLifecycle()
    val plan = plans.firstOrNull { it.id == planId }
    val draft = drafts[planId]
    LaunchedEffect(plan == null || draft == null) { if (plan == null || draft == null) onBack() }
    if (plan == null || draft == null) return

    val units = remember(planId) { repo.cachedUnits(planId)?.units.orEmpty() }
    var filter by rememberSaveable { mutableStateOf(UnitFilter.ALL) }
    var showInactive by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<UnitEntry?>(null) }
    var camera by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SecureScreen()

    val current = remember(units, draft, plan) { units.associate { it.unitId to InspectionRules.currentResult(it, draft, plan) } }
    val active = units.filter { it.active }
    val checked = active.count { current[it.unitId].let { r -> r != null && r != UnitResult.PENDING } }
    fun keep(u: InspectionUnitDto) = when (filter) {
        UnitFilter.ALL -> true
        UnitFilter.TO_CHECK -> current[u.unitId].let { it == null || it == UnitResult.PENDING }
        UnitFilter.FLAGGED -> current[u.unitId] in InspectionRules.flagged
    }

    // Camera over everything while a photo is taken for the unit being edited.
    val photoFor = editing
    if (camera && photoFor != null) {
        PlatformBackHandler { camera = false }
        val resultWord = photoFor.result?.let { stringResource(resultStyle(it).label) }.orEmpty()
        val unitWord = stringResource(Res.string.insp_unit_word)
        CameraCapture(
            hint = stringResource(Res.string.insp_photo_hint),
            speakText = stringResource(Res.string.speak_insp_photo),
            showFrame = false,
        ) { file ->
            scope.launch {
                val now = Instant.now()
                val lines = listOfNotNull(
                    "${plan.propertyCode} · $unitWord ${photoFor.unitCode.ifBlank { "?" }} · $resultWord",
                    formatStamp(now.toLocalDateTime(localZone)) + (draft.latitude?.let { " · " + formatDecimal(it, 5) + ", " + formatDecimal(draft.longitude ?: 0.0, 5) } ?: ""),
                    inspector.takeIf { it.isNotBlank() },
                )
                withContext(ioDispatcher) { runCatching { stampPhoto(file, lines) } }
                editing = photoFor.copy(photos = photoFor.photos + EvidencePhoto(randomUuid(), file.path, now.toString()))
                camera = false
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(Res.string.insp_units_title, plan.propertyCode), stringResource(Res.string.speak_insp_units), onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgressStrip(active.size, checked, active.count { current[it.unitId] in InspectionRules.flagged })
            Text(stringResource(Res.string.insp_checked_of, checked, active.size), color = AppColors.SubInk)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(Res.string.insp_f_all, units.size), filter == UnitFilter.ALL) { filter = UnitFilter.ALL }
                Chip(stringResource(Res.string.insp_f_to_check, active.size - checked), filter == UnitFilter.TO_CHECK) { filter = UnitFilter.TO_CHECK }
                Chip(stringResource(Res.string.insp_f_flagged, current.values.count { it in InspectionRules.flagged }), filter == UnitFilter.FLAGGED, Icons.Rounded.Flag) { filter = UnitFilter.FLAGGED }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val shownActive = active.filter(::keep)
            shownActive.groupBy { it.buildingName.orEmpty() }.forEach { (building, list) ->
                item(key = "b-$building") { GroupTitle(building.ifBlank { plan.propertyCode }, list.size) }
                items(list, key = { it.unitId }) { u ->
                    SwipeUnitRow(
                        unit = u,
                        entry = draft.entries[u.unitId],
                        result = current[u.unitId],
                        onOpen = { editing = draft.entries[u.unitId] ?: InspectionRepository.entryFor(u) },
                        onAsRecorded = { repo.saveEntry(planId, (draft.entries[u.unitId] ?: InspectionRepository.entryFor(u)).copy(result = UnitResult.AS_RECORDED)) },
                    )
                }
            }
            if (active.isNotEmpty() && filter == UnitFilter.ALL) {
                item(key = "hint") { Text(stringResource(Res.string.insp_swipe_hint), color = AppColors.SubInk, style = MaterialTheme.typography.bodyMedium) }
            }
            val newUnits = draft.entries.values.filter { it.isNew }
            if (newUnits.isNotEmpty()) {
                item(key = "new") { GroupTitle(stringResource(Res.string.insp_new_units), newUnits.size) }
                items(newUnits, key = { it.key }) { e -> UnitRow(e.unitCode, e.buildingName, InspectionRules.categoryName(e.category), e.result, e.photos.size, isNew = true) { editing = e } }
            }
            val inactive = units.filter { !it.active && keep(it) }
            if (inactive.isNotEmpty()) {
                item(key = "inactive") {
                    TextButton(onClick = { showInactive = !showInactive }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (showInactive) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.insp_inactive_section, inactive.size), style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (showInactive) items(inactive, key = { it.unitId }) { u ->
                    UnitRow(u.unitCode, u.subTenantName, u.categoryName, current[u.unitId], draft.entries[u.unitId]?.photos?.size ?: 0, inactive = true) {
                        editing = draft.entries[u.unitId] ?: InspectionRepository.entryFor(u)
                    }
                }
            }
            item(key = "add") {
                BigButton(
                    stringResource(Res.string.insp_add_unit), { editing = InspectionRepository.newUnitEntry() },
                    icon = Icons.Rounded.AddBox, kind = BigButtonKind.Secondary, minHeight = 56.dp,
                )
            }
        }
        Box(Modifier.padding(16.dp)) {
            BigButton(stringResource(Res.string.insp_review), onReview, icon = Icons.Rounded.FactCheck, enabled = draft.recorded.isNotEmpty())
        }
    }

    editing?.let { e ->
        val unit = units.firstOrNull { it.unitId == e.unitId }
        UnitSheet(
            entry = e,
            unit = unit,
            buildings = units.mapNotNull { it.buildingName }.distinct(),
            onChange = { editing = it },
            onTakePhoto = { camera = true },
            onSave = {
                repo.saveEntry(planId, it)
                editing = null
            },
            onRemove = if (e.isNew && draft.entries.containsKey(e.key)) ({ repo.removeEntry(planId, e.key); editing = null }) else null,
            onDismiss = { editing = null },
        )
    }
}


/** Checked units in green, flagged in their own colour, the rest grey; the words below say the same. */
@Composable
private fun ProgressStrip(total: Int, checked: Int, flagged: Int) {
    val okCount = (checked - flagged).coerceAtLeast(0)
    val rest = (total - checked).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(AppColors.Line)) {
        if (okCount > 0) Box(Modifier.weight(okCount.toFloat()).fillMaxHeight().background(AppColors.Ok))
        if (flagged > 0) Box(Modifier.weight(flagged.toFloat()).fillMaxHeight().background(AppColors.Sublet))
        if (rest > 0) Box(Modifier.weight(rest.toFloat()).fillMaxHeight())
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = if (icon != null) { { Icon(icon, null, Modifier.size(18.dp)) } } else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AppColors.Navy, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White,
        ),
        modifier = Modifier.heightIn(min = 44.dp),
    )
}

@Composable
private fun GroupTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = AppColors.SubInk, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count", fontFamily = NumberFont, color = AppColors.SubInk)
    }
}

/** A unit row that records "as recorded" when swiped right. It springs back; the result shows on the row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeUnitRow(unit: InspectionUnitDto, entry: UnitEntry?, result: UnitResult?, onOpen: () -> Unit, onAsRecorded: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) onAsRecorded()
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(AppColors.Ok).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Color.White)
                Text(stringResource(Res.string.insp_r_as_recorded), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        },
    ) {
        UnitRow(unit.unitCode, unit.subTenantName, unit.categoryName, result, entry?.photos?.size ?: 0, onClick = onOpen)
    }
}

@Composable
private fun UnitRow(
    code: String,
    tenant: String?,
    category: String?,
    result: UnitResult?,
    photos: Int,
    isNew: Boolean = false,
    inactive: Boolean = false,
    onClick: () -> Unit,
) {
    val flagged = result in InspectionRules.flagged
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = AppColors.Card,
        border = BorderStroke(if (flagged) 2.dp else 1.5.dp, if (flagged) resultStyle(result!!).fg else AppColors.Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(if (isNew) AppColors.WarnTint else AppColors.TaupeTint),
                contentAlignment = Alignment.Center,
            ) {
                Text(code.ifBlank { "?" }, fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = if (code.length > 4) 12.sp else 16.sp, color = if (isNew) AppColors.Warn else AppColors.Taupe, maxLines = 1)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    tenant?.takeIf { it.isNotBlank() } ?: stringResource(if (isNew) Res.string.insp_new_unit_short else Res.string.insp_no_subtenant),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (photos > 0) {
                        Icon(Icons.Rounded.PhotoCamera, null, tint = AppColors.SubInk, modifier = Modifier.size(16.dp))
                        Text("$photos", color = AppColors.SubInk)
                    }
                    Text(
                        listOfNotNull(category, if (inactive) stringResource(Res.string.insp_inactive_word) else null).joinToString(" · "),
                        color = AppColors.SubInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (result != null) ResultPill(result) else NotChecked()
            }
        }
    }
}

@Composable
private fun NotChecked() {
    Row(
        Modifier.border(1.5.dp, AppColors.SubInk, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = AppColors.SubInk, modifier = Modifier.size(16.dp))
        Text(stringResource(Res.string.insp_not_checked), color = AppColors.SubInk, style = MaterialTheme.typography.labelMedium)
    }
}
