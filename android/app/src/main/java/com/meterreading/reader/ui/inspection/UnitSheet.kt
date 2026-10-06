package com.meterreading.reader.ui.inspection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meterreading.reader.R
import com.meterreading.reader.api.InspectionUnitDto
import com.meterreading.reader.data.EvidencePhoto
import com.meterreading.reader.data.InspectionRules
import com.meterreading.reader.data.UnitEntry
import com.meterreading.reader.data.UnitResult
import com.meterreading.reader.ui.capture.rememberPhoto
import com.meterreading.reader.ui.components.*
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.NumberFont
import java.io.File

/**
 * Spec FR-032/033/035: one unit, in a sheet over the list. Six large result choices, each with its icon and
 * word; the people seen; and, only for results that need them, who is there, why, and photos. "Save unit"
 * stays off until the unit has what its result needs, and the missing item is said in words.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UnitSheet(
    entry: UnitEntry,
    unit: InspectionUnitDto?,
    buildings: List<String>,
    onChange: (UnitEntry) -> Unit,
    onTakePhoto: () -> Unit,
    onSave: (UnitEntry) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val problem = InspectionRules.problem(entry)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = AppColors.Background) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (entry.isNew) stringResource(R.string.insp_new_unit_title) else stringResource(R.string.insp_unit_title, entry.unitCode),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    (unit?.category ?: entry.category)?.let { Text(it.replace(">", " › "), color = AppColors.SubInk) }
                }
                SpeakButton(stringResource(if (entry.isNew) R.string.speak_insp_new_unit else R.string.speak_insp_unit))
            }

            if (unit != null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.Card).padding(12.dp)) {
                    Text(stringResource(R.string.insp_on_record), style = MaterialTheme.typography.labelMedium, color = AppColors.SubInk)
                    Text(unit.subTenantName ?: stringResource(R.string.insp_no_subtenant), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (unit.active) R.string.insp_active_word else R.string.insp_inactive_word), color = AppColors.SubInk)
                    unit.last?.let { last ->
                        runCatching { UnitResult.valueOf(last.result) }.getOrNull()?.let { r ->
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.insp_last_time), color = AppColors.SubInk)
                                ResultPill(r)
                            }
                        }
                    }
                }
            } else {
                NewUnitFields(entry, buildings, onChange)
            }

            Text(stringResource(R.string.insp_q_found), style = MaterialTheme.typography.titleLarge)
            UnitResult.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { r ->
                        ResultTile(r, entry.result == r, Modifier.weight(1f)) {
                            // Changing the result drops reasons that belong to the old one.
                            onChange(entry.copy(result = r, reasons = entry.reasons.filter { it in InspectionRules.reasonsFor(r) }))
                        }
                    }
                }
            }

            Text(stringResource(R.string.insp_people), style = MaterialTheme.typography.titleMedium)
            Stepper(entry.peopleSeen ?: 0) { onChange(entry.copy(peopleSeen = it)) }

            if (InspectionRules.hasDetails(entry.result)) {
                if (entry.result in InspectionRules.flagged) {
                    OutlinedTextField(
                        value = entry.occupantName,
                        onValueChange = { onChange(entry.copy(occupantName = it.take(200))) },
                        label = { Text(stringResource(if (InspectionRules.needsOccupant(entry.result)) R.string.insp_who else R.string.insp_who_optional)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val reasons = InspectionRules.reasonsFor(entry.result)
                if (reasons.isNotEmpty()) {
                    Text(stringResource(R.string.insp_why), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        reasons.forEach { code ->
                            val on = code in entry.reasons
                            FilterChip(
                                selected = on,
                                onClick = { onChange(entry.copy(reasons = if (on) entry.reasons - code else entry.reasons + code)) },
                                label = { Text(stringResource(reasonLabel(code)), style = MaterialTheme.typography.labelMedium) },
                                leadingIcon = if (on) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null,
                                modifier = Modifier.heightIn(min = 44.dp),
                            )
                        }
                    }
                }
                Photos(entry, onTakePhoto, onRemove = { photo ->
                    File(photo.path).delete()
                    onChange(entry.copy(photos = entry.photos - photo))
                })
                OutlinedTextField(
                    value = entry.note,
                    onValueChange = { onChange(entry.copy(note = it.take(500))) },
                    label = { Text(stringResource(R.string.insp_note)) },
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (entry.result == UnitResult.AS_RECORDED && entry.photos.isNotEmpty()) {
                Photos(entry, onTakePhoto, onRemove = { photo ->
                    File(photo.path).delete()
                    onChange(entry.copy(photos = entry.photos - photo))
                })
            }

            if (problem != null && entry.result != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Info, null, tint = AppColors.Warn)
                    Text(stringResource(problemLabel(problem)), color = AppColors.Warn, style = MaterialTheme.typography.titleMedium)
                }
            }
            BigButton(stringResource(R.string.insp_save_unit), { onSave(entry) }, icon = Icons.Rounded.Check, enabled = problem == null)
            if (onRemove != null) {
                TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.DeleteOutline, null, tint = AppColors.Bad)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.insp_remove_unit), color = AppColors.Bad, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewUnitFields(entry: UnitEntry, buildings: List<String>, onChange: (UnitEntry) -> Unit) {
    Banner(stringResource(R.string.insp_new_unit_banner), Icons.Rounded.Info, AppColors.Warn, AppColors.WarnTint)
    OutlinedTextField(
        value = entry.unitCode,
        onValueChange = { onChange(entry.copy(unitCode = it.uppercase().take(30))) },
        label = { Text(stringResource(R.string.insp_unit_door)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = NumberFont),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (buildings.isNotEmpty()) {
        Text(stringResource(R.string.insp_building), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            buildings.forEach { b ->
                FilterChip(selected = entry.buildingName == b, onClick = { onChange(entry.copy(buildingName = b)) }, label = { Text(b) }, modifier = Modifier.heightIn(min = 44.dp))
            }
        }
    }
    Text(stringResource(R.string.insp_type), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        newUnitTypes.forEach { (label, path) ->
            FilterChip(selected = entry.category == path, onClick = { onChange(entry.copy(category = path)) }, label = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 44.dp))
        }
    }
}

/** A large result choice: icon, word and a few words of meaning. The chosen one has a thick border and a tick. */
@Composable
private fun ResultTile(r: UnitResult, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val s = resultStyle(r)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) s.bg else AppColors.Card,
        border = BorderStroke(if (selected) 3.dp else 1.5.dp, if (selected) s.fg else AppColors.Line),
        modifier = modifier.heightIn(min = 96.dp),
    ) {
        Box {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(s.icon, null, tint = s.fg, modifier = Modifier.size(30.dp))
                Text(stringResource(s.label), style = MaterialTheme.typography.labelMedium, color = s.fg, textAlign = TextAlign.Center)
                Text(stringResource(s.hint), fontSize = 11.sp, lineHeight = 13.sp, color = AppColors.SubInk, textAlign = TextAlign.Center)
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = s.fg, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp))
        }
    }
}

/** − and + with the number between, big enough for gloves. */
@Composable
private fun Stepper(value: Int, onValue: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AppColors.Card),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(Icons.Rounded.Remove, stringResource(R.string.insp_less), enabled = value > 0) { onValue((value - 1).coerceAtLeast(0)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 30.sp)
            Text(stringResource(R.string.insp_people_word), color = AppColors.SubInk, style = MaterialTheme.typography.labelMedium)
        }
        StepButton(Icons.Rounded.Add, stringResource(R.string.insp_more), enabled = value < InspectionRules.MAX_PEOPLE) { onValue(value + 1) }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = AppColors.NavyTint, contentColor = AppColors.Navy, modifier = Modifier.size(width = 72.dp, height = 64.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, modifier = Modifier.size(32.dp)) }
    }
}

@Composable
private fun Photos(entry: UnitEntry, onTakePhoto: () -> Unit, onRemove: (EvidencePhoto) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.insp_photos, entry.photos.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (InspectionRules.needsPhoto(entry.result) && entry.photos.isEmpty()) {
            Pill(stringResource(R.string.insp_photo_needed), AppColors.Sublet, AppColors.SubletTint, Icons.Rounded.PhotoCamera)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        entry.photos.takeLast(InspectionRules.MAX_PHOTOS).forEach { p -> Thumb(p, Modifier.weight(1f)) { onRemove(p) } }
        if (entry.photos.size < InspectionRules.MAX_PHOTOS) {
            Surface(
                onClick = onTakePhoto,
                shape = RoundedCornerShape(12.dp),
                color = AppColors.NavyTint,
                contentColor = AppColors.Navy,
                border = BorderStroke(2.dp, AppColors.Navy),
                modifier = Modifier.weight(1f).aspectRatio(1f),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.AddAPhoto, null, Modifier.size(30.dp))
                    Text(stringResource(R.string.insp_add_photo), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                }
            }
        }
        repeat((3 - entry.photos.size - 1).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun Thumb(photo: EvidencePhoto, modifier: Modifier, onRemove: () -> Unit) {
    val image = rememberPhoto(File(photo.path), maxSize = 300)
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2D31))) {
        if (image != null) Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Surface(onClick = onRemove, shape = CircleShape, color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, stringResource(R.string.insp_remove_photo), Modifier.size(20.dp)) }
        }
    }
}
