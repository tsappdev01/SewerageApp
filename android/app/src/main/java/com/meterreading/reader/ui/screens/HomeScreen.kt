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
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.meterreading.reader.util.formatDayTime
import com.meterreading.reader.util.formatTime
import com.meterreading.reader.util.rememberVoiceInput

@Composable
fun HomeScreen(
    onStart: (String) -> Unit,
    onFind: (voiceText: String?) -> Unit,
    onZones: () -> Unit,
    onReadings: () -> Unit,
    onSummary: () -> Unit,
    onSettings: () -> Unit,
    onInspections: () -> Unit = {},
) {
    val repo = AppGraph.repository
    val meters by repo.meters.collectAsStateWithLifecycle()
    val readings by repo.readings.collectAsStateWithLifecycle()
    val readerName by repo.readerName.collectAsStateWithLifecycle()
    val savedListFrom by repo.savedListFrom.collectAsStateWithLifecycle()
    val total = meters.size
    val done = meters.count { !it.state.canCapture }
    val queued = readings.count { it.state == ReadingState.QUEUED }
    val readAgain = meters.count { it.state == ReadingState.READ_AGAIN }
    val next = remember(meters) { repo.nextMeter(meters) }
    val lastUpload = readings.filter { it.state != ReadingState.QUEUED }.maxOfOrNull { it.capturedAt }
    val context = LocalContext.current
    val voiceMissing = stringResource(R.string.search_voice_missing)
    val voice = rememberVoiceInput(stringResource(R.string.search_placeholder)) { onFind(it) }

    // Fresh meter states each time Home opens (another reader may have read some).
    LaunchedEffect(Unit) { repo.refresh() }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.hello, readerName), stringResource(R.string.speak_home), onSettings = onSettings)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (queued == 0) {
                Pill(
                    listOfNotNull(stringResource(R.string.all_sent), lastUpload?.let(::formatTime)).joinToString(" · "),
                    AppColors.Ok, AppColors.OkTint, Icons.Rounded.CloudDone,
                )
            } else {
                Pill(stringResource(R.string.waiting_count, queued), AppColors.Queued, AppColors.QueuedTint, Icons.Rounded.CloudUpload)
            }
            // FR-020.1: opened without signal from the phone's saved list; it updates once the server is reached.
            savedListFrom?.let {
                Pill(stringResource(R.string.saved_list_from, formatDayTime(it)), AppColors.Warn, AppColors.WarnTint, Icons.Rounded.CloudOff)
            }
            ProgressCard(done, total)
            if (next != null) {
                val property = repo.property(next.propertyCode)
                BigButton(
                    text = stringResource(R.string.start),
                    subtitle = stringResource(R.string.next_at, next.zoneCode, property.displayName),
                    onClick = { onStart(next.id) },
                    icon = Icons.Rounded.PlayArrow,
                    minHeight = 96.dp,
                )
            } else {
                Banner(stringResource(R.string.all_done_today), Icons.Rounded.CheckCircle, AppColors.Ok, AppColors.OkTint)
            }
            SearchField(
                text = "",
                placeholder = stringResource(R.string.search_placeholder),
                onClick = { onFind(null) },
                onMic = { if (!voice()) Toast.makeText(context, voiceMissing, Toast.LENGTH_LONG).show() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeTile(Icons.Rounded.Place, stringResource(R.string.my_zones), onZones, Modifier.weight(1f))
                HomeTile(Icons.AutoMirrored.Rounded.ListAlt, stringResource(R.string.my_readings), onReadings, Modifier.weight(1f), badge = readAgain)
                HomeTile(Icons.Rounded.BarChart, stringResource(R.string.summary), onSummary, Modifier.weight(1f), badge = queued, badgeColor = AppColors.Queued)
            }
            // Spec §16: shown only when the server has field inspection set up.
            val inspections by AppGraph.inspectionsFlow.collectAsStateWithLifecycle()
            inspections?.let { InspectionCard(it, onInspections) }
        }
    }
}

/** Field inspection on Home: today's and late plan rows, and visits waiting on the phone. */
@Composable
private fun InspectionCard(repo: InspectionRepository, onClick: () -> Unit) {
    val available by repo.available.collectAsStateWithLifecycle()
    val plans by repo.plans.collectAsStateWithLifecycle()
    val today by repo.today.collectAsStateWithLifecycle()
    val waiting by repo.waitingVisits.collectAsStateWithLifecycle()
    LaunchedEffect(repo) { repo.refreshPlan() }
    if (available != true) return
    val todayCount = InspectionRules.plansFor(PlanTab.TODAY, plans, today).size
    val late = InspectionRules.plansFor(PlanTab.LATE, plans, today).size
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = AppColors.Card,
        border = BorderStroke(2.dp, AppColors.Navy),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(AppColors.NavyTint), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.FactCheck, null, tint = AppColors.Navy, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.insp_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.insp_tile_counts, todayCount, late), color = if (late > 0) AppColors.Bad else AppColors.SubInk)
                if (waiting > 0) Pill(stringResource(R.string.insp_waiting, waiting), AppColors.Queued, AppColors.QueuedTint, Icons.Rounded.CloudUpload)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AppColors.SubInk)
        }
    }
}

@Composable
private fun ProgressCard(done: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.Card)
            .border(1.5.dp, AppColors.Line, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProgressRing(done, total)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$done", fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 36.sp)
            Text(stringResource(R.string.done_of, total), color = AppColors.SubInk)
            if (total > done) Pill(stringResource(R.string.left_count, total - done), AppColors.Warn, AppColors.WarnTint, Icons.Rounded.Speed)
        }
    }
}

@Composable
private fun HomeTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    badge: Int = 0,
    badgeColor: Color = AppColors.Warn,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 104.dp),
        shape = RoundedCornerShape(18.dp),
        color = AppColors.Card,
        border = BorderStroke(1.5.dp, AppColors.Line),
    ) {
        Box {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 104.dp)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(34.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
            if (badge > 0) {
                Text(
                    "$badge",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
        }
    }
}
