package com.meterreading.reader.ui.screens

import com.meterreading.reader.platform.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.meterreading.reader.resources.*
import com.meterreading.reader.data.*
import com.meterreading.reader.ui.components.*
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.NumberFont
import com.meterreading.reader.util.formatReading

@Composable
fun ZonesScreen(onZone: (String) -> Unit, onBack: () -> Unit) {
    val repo = AppGraph.repository
    val meters by repo.meters.collectAsStateWithLifecycle()
    val zones = remember(meters) { repo.zoneProgress(meters) }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(Res.string.my_zones), stringResource(Res.string.speak_zones), onBack)
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(zones, key = { it.code }) { zone ->
                val finished = zone.done == zone.total
                ListCard(onClick = { onZone(zone.code) }, finished = finished) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (finished) AppColors.OkTint else AppColors.NavyTint),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (finished) Icons.Rounded.Check else Icons.Rounded.Place, null, tint = if (finished) AppColors.Ok else AppColors.Navy, modifier = Modifier.size(30.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(zone.code, fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                        Text(
                            if (finished) stringResource(Res.string.zone_done, zone.total)
                            else stringResource(Res.string.zone_progress, zone.done, zone.total, zone.total - zone.done),
                            color = AppColors.SubInk,
                        )
                        ProgressBar(zone.done, zone.total)
                    }
                }
            }
        }
    }
}

@Composable
fun PropertiesScreen(
    zoneCode: String,
    onProperty: (String) -> Unit,
    onFind: (zone: String, voiceText: String?) -> Unit,
    onBack: () -> Unit,
) {
    val repo = AppGraph.repository
    val meters by repo.meters.collectAsStateWithLifecycle()
    var done by rememberSaveable { mutableStateOf(DoneFilter.TO_READ) }
    var type by rememberSaveable { mutableStateOf<MeterType?>(null) }
    val all = remember(meters, zoneCode) { repo.propertiesIn(zoneCode, meters) }
    val shown = remember(all, done, type) { Search.run(SearchQuery(done = done, type = type), all).properties }
    val toRead = all.count { p -> p.meters.any { it.state.canCapture } }
    val voiceMissing = stringResource(Res.string.search_voice_missing)
    val voice = rememberVoiceInput(stringResource(Res.string.search_in_zone, zoneCode)) { onFind(zoneCode, it) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(Res.string.zone_title, zoneCode), stringResource(Res.string.speak_properties), onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(
                text = "",
                placeholder = stringResource(Res.string.search_in_zone, zoneCode),
                onClick = { onFind(zoneCode, null) },
                onMic = { if (!voice()) Messages.show(voiceMissing) },
            )
            FilterRow(done, { done = it }, type, { type = it }, toRead = toRead, doneCount = all.size - toRead, total = all.size)
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown, key = { it.property.code }) { p ->
                PropertyCard(p, query = "", onClick = { onProperty(p.property.code) })
            }
        }
    }
}

/** Shared filter chips: to read / done, and meter type. */
@Composable
fun FilterRow(
    done: DoneFilter,
    onDone: (DoneFilter) -> Unit,
    type: MeterType?,
    onType: (MeterType?) -> Unit,
    toRead: Int,
    doneCount: Int,
    total: Int,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChoiceChip(stringResource(Res.string.filter_all, total), done == DoneFilter.ALL, { onDone(DoneFilter.ALL) })
        ChoiceChip(stringResource(Res.string.filter_to_read, toRead), done == DoneFilter.TO_READ, { onDone(DoneFilter.TO_READ) })
        ChoiceChip(stringResource(Res.string.filter_done, doneCount), done == DoneFilter.DONE, { onDone(DoneFilter.DONE) })
        MeterType.entries.forEach { t ->
            ChoiceChip(
                label = null,
                selected = type == t,
                onClick = { onType(if (type == t) null else t) },
                icon = meterTypeIcon(t),
                iconTint = if (t == MeterType.IRRIGATION) AppColors.Irrigation else AppColors.Sewerage,
                description = stringResource(meterTypeLabel(t)),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PropertyCard(p: PropertyProgress, query: String, onClick: () -> Unit, showZone: Boolean = false) {
    val finished = p.meters.none { it.state.canCapture }
    ListCard(onClick = onClick, finished = finished) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HighlightedText(p.property.code, query, MaterialTheme.typography.headlineMedium.copy(fontFamily = NumberFont))
            // The tenant's company; highlighted when the search matched it.
            HighlightedText(p.property.displayName, query, MaterialTheme.typography.titleMedium, maxLines = 2)
            if (showZone) {
                Text(stringResource(Res.string.property_meta, p.property.zoneCode, p.done, p.meters.size), color = AppColors.SubInk)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                p.meters.forEach { MeterTypeIcon(it.type, size = 30.dp, outlined = !it.state.canCapture) }
            }
        }
        GoCircle(finished)
    }
}

@Composable
fun MetersScreen(propertyCode: String, onMeter: (String) -> Unit, onBack: () -> Unit) {
    val repo = AppGraph.repository
    val meters by repo.meters.collectAsStateWithLifecycle()
    val list = remember(meters, propertyCode) { repo.metersAt(propertyCode, meters) }
    val next = list.firstOrNull { it.state.canCapture }
    var again by remember { mutableStateOf<Meter?>(null) }
    again?.let { m -> ReadAgainDialog(m, onYes = { again = null; onMeter(m.id) }, onNo = { again = null }) }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(propertyCode, stringResource(Res.string.speak_meters), onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.id }) { m -> MeterCard(m, query = "", highlighted = m == next, onClick = { onMeter(m.id) }, onReadAgain = { again = m }) }
        }
        if (list.isNotEmpty()) FinalizeBar(propertyCode, list)
    }
}

/** FR-037: "Finalize property", once every meter here is read and sent. The screen's one primary button. */
@Composable
private fun FinalizeBar(propertyCode: String, meters: List<Meter>) {
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val left = FinalizeRules.left(meters)
    val noSignal = stringResource(Res.string.finalize_no_signal)
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            icon = { Icon(Icons.Rounded.TaskAlt, null, tint = AppColors.Navy, modifier = Modifier.size(40.dp)) },
            title = { Text(stringResource(Res.string.finalize_title, propertyCode)) },
            text = { Text(stringResource(Res.string.finalize_text), style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                Button(
                    onClick = {
                        asking = false
                        sending = true
                        scope.launch {
                            val result = AppGraph.repository.finalizeProperty(propertyCode)
                            sending = false
                            Messages.show(
                                when (result) {
                                    is FinalizeResult.Done -> getString(Res.string.finalize_done, result.rows)
                                    FinalizeResult.NoSignal -> noSignal
                                    is FinalizeResult.Refused -> result.message
                                },
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Navy),
                    modifier = Modifier.heightIn(min = 52.dp),
                ) { Text(stringResource(Res.string.finalize_yes)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }, modifier = Modifier.heightIn(min = 52.dp)) { Text(stringResource(Res.string.finalize_no)) } },
        )
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        BigButton(
            text = if (sending) stringResource(Res.string.sending) else stringResource(Res.string.finalize_property),
            subtitle = if (left > 0) stringResource(Res.string.finalize_left, left) else null,
            onClick = { asking = true },
            icon = Icons.Rounded.TaskAlt,
            enabled = left == 0 && !sending,
        )
    }
}

/** A meter already read this month can be read again; the reader is asked first, as the new reading replaces it. */
@Composable
fun ReadAgainDialog(m: Meter, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text(stringResource(Res.string.read_again_title, m.number)) },
        text = { Text(stringResource(Res.string.read_again_text), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Button(onClick = onYes, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Navy), modifier = Modifier.heightIn(min = 52.dp)) {
                Text(stringResource(Res.string.read_again_yes))
            }
        },
        dismissButton = { TextButton(onClick = onNo, modifier = Modifier.heightIn(min = 52.dp)) { Text(stringResource(Res.string.read_again_no)) } },
    )
}

@Composable
fun MeterCard(m: Meter, query: String, highlighted: Boolean, onClick: () -> Unit, onReadAgain: (() -> Unit)? = null) {
    val tap = when {
        m.state.canCapture -> onClick
        m.state.canReadAgain -> onReadAgain
        else -> null
    }
    ListCard(onClick = tap, finished = !m.state.canCapture, highlighted = highlighted) {
        MeterTypeIcon(m.type, size = 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HighlightedText(m.number, query, MaterialTheme.typography.titleLarge.copy(fontFamily = NumberFont, fontSize = 24.sp))
            if (m.state == ReadingState.PENDING) {
                Text(
                    stringResource(meterTypeLabel(m.type)) + " · " +
                        (m.previousReading?.takeUnless { m.isFirstReading }?.let { stringResource(Res.string.last_reading, formatReading(it)) } ?: stringResource(Res.string.new_meter_first)),
                    color = AppColors.SubInk,
                )
            } else {
                StateChip(m.state)
            }
            m.supervisorNote?.let { Text(stringResource(Res.string.supervisor_note, it), color = AppColors.Bad, style = MaterialTheme.typography.labelMedium) }
        }
        GoCircle(!m.state.canCapture)
    }
}

@Composable
fun ListCard(
    onClick: (() -> Unit)?,
    finished: Boolean = false,
    highlighted: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val border = BorderStroke(if (highlighted) 3.dp else 1.5.dp, if (highlighted) AppColors.Navy else AppColors.Line)
    val row: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 84.dp)
                .padding(14.dp)
                .alpha(if (finished) 0.75f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = AppColors.Card, border = border, modifier = Modifier.fillMaxWidth()) { row() }
    } else {
        Surface(shape = shape, color = AppColors.Card, border = border, modifier = Modifier.fillMaxWidth()) { row() }
    }
}

@Composable
fun GoCircle(finished: Boolean) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (finished) AppColors.Ok else AppColors.Navy),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (finished) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}
