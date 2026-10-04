package com.meterreading.reader.ui.screens

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meterreading.reader.R
import com.meterreading.reader.data.*
import com.meterreading.reader.ui.components.*
import com.meterreading.reader.ui.theme.AppColors
import com.meterreading.reader.ui.theme.NumberFont
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.meterreading.reader.util.formatReading
import com.meterreading.reader.util.rememberVoiceInput

@Composable
fun ZonesScreen(onZone: (String) -> Unit, onBack: () -> Unit) {
    val repo = AppGraph.repository
    val meters by repo.meters.collectAsStateWithLifecycle()
    val zones = remember(meters) { repo.zoneProgress(meters) }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.my_zones), stringResource(R.string.speak_zones), onBack)
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
                            if (finished) stringResource(R.string.zone_done, zone.total)
                            else stringResource(R.string.zone_progress, zone.done, zone.total, zone.total - zone.done),
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
    val context = LocalContext.current
    val voiceMissing = stringResource(R.string.search_voice_missing)
    val voice = rememberVoiceInput(stringResource(R.string.search_in_zone, zoneCode)) { onFind(zoneCode, it) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.zone_title, zoneCode), stringResource(R.string.speak_properties), onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(
                text = "",
                placeholder = stringResource(R.string.search_in_zone, zoneCode),
                onClick = { onFind(zoneCode, null) },
                onMic = { if (!voice()) Toast.makeText(context, voiceMissing, Toast.LENGTH_LONG).show() },
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
        ChoiceChip(stringResource(R.string.filter_all, total), done == DoneFilter.ALL, { onDone(DoneFilter.ALL) })
        ChoiceChip(stringResource(R.string.filter_to_read, toRead), done == DoneFilter.TO_READ, { onDone(DoneFilter.TO_READ) })
        ChoiceChip(stringResource(R.string.filter_done, doneCount), done == DoneFilter.DONE, { onDone(DoneFilter.DONE) })
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
            Text(
                if (showZone) stringResource(R.string.property_meta, p.property.zoneCode, p.done, p.meters.size) else p.property.name,
                color = AppColors.SubInk,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                p.meters.forEach { MeterTypeIcon(it.type, size = 30.dp, outlined = !it.state.canCapture) }
            }
        }
        GoCircle(finished)
    }
}

@Composable
fun MetersScreen(propertyCode: String, onMeter: (Long) -> Unit, onBack: () -> Unit) {
    val repo = AppGraph.repository
    val meters by repo.meters.collectAsStateWithLifecycle()
    val list = remember(meters, propertyCode) { repo.metersAt(propertyCode, meters) }
    val next = list.firstOrNull { it.state.canCapture }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(propertyCode, stringResource(R.string.speak_meters), onBack)
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.id }) { m -> MeterCard(m, query = "", highlighted = m == next, onClick = { onMeter(m.id) }) }
        }
    }
}

@Composable
fun MeterCard(m: Meter, query: String, highlighted: Boolean, onClick: () -> Unit) {
    ListCard(onClick = if (m.state.canCapture) onClick else null, finished = !m.state.canCapture, highlighted = highlighted) {
        MeterTypeIcon(m.type, size = 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HighlightedText(m.number, query, MaterialTheme.typography.titleLarge.copy(fontFamily = NumberFont, fontSize = 24.sp))
            if (m.state == ReadingState.PENDING) {
                Text(
                    stringResource(meterTypeLabel(m.type)) + " · " +
                        (m.previousReading?.let { stringResource(R.string.last_reading, formatReading(it)) } ?: stringResource(R.string.new_meter_first)),
                    color = AppColors.SubInk,
                )
            } else {
                StateChip(m.state)
            }
            m.supervisorNote?.let { Text(stringResource(R.string.supervisor_note, it), color = AppColors.Bad, style = MaterialTheme.typography.labelMedium) }
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
