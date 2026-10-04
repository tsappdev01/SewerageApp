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
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.meterreading.reader.util.formatReading
import com.meterreading.reader.util.formatTime
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector

private enum class ReadingsFilter { ALL, WAITING, READ_AGAIN, CHECKING }

@Composable
fun MyReadingsScreen(onCapture: (String) -> Unit, onBack: () -> Unit) {
    val repo = AppGraph.repository
    val meters by repo.meters.collectAsStateWithLifecycle()
    val readings by repo.readings.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(ReadingsFilter.ALL) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val noSignal = stringResource(R.string.no_signal_now)
    val byId = meters.associateBy { it.id }
    val readAgain = meters.filter { it.state == ReadingState.READ_AGAIN }
    val waiting = readings.count { it.state == ReadingState.QUEUED }
    val checking = readings.count { it.state == ReadingState.CHECKING }
    val shownReadings = readings.filter {
        when (filter) {
            ReadingsFilter.ALL -> true
            ReadingsFilter.WAITING -> it.state == ReadingState.QUEUED
            ReadingsFilter.READ_AGAIN -> false
            ReadingsFilter.CHECKING -> it.state == ReadingState.CHECKING
        }
    }
    val showReadAgain = filter == ReadingsFilter.ALL || filter == ReadingsFilter.READ_AGAIN

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.my_readings), stringResource(R.string.speak_readings), onBack)
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChoiceChip(stringResource(R.string.filter_all, readings.size), filter == ReadingsFilter.ALL, { filter = ReadingsFilter.ALL })
            ChoiceChip("$waiting", filter == ReadingsFilter.WAITING, { filter = ReadingsFilter.WAITING }, Icons.Rounded.CloudUpload, AppColors.Queued, stringResource(R.string.state_queued))
            ChoiceChip("${readAgain.size}", filter == ReadingsFilter.READ_AGAIN, { filter = ReadingsFilter.READ_AGAIN }, Icons.Rounded.Close, AppColors.Bad, stringResource(R.string.state_read_again))
            ChoiceChip("$checking", filter == ReadingsFilter.CHECKING, { filter = ReadingsFilter.CHECKING }, Icons.Rounded.Warning, AppColors.Warn, stringResource(R.string.state_checking))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showReadAgain) {
                items(readAgain, key = { "again-${it.id}" }) { m -> MeterCard(m, query = "", highlighted = true, onClick = { onCapture(m.id) }) }
            }
            items(shownReadings, key = { it.transactionId }) { r ->
                val m = byId[r.meterId] ?: return@items
                ReadingRow(m, r)
            }
        }
        if (waiting > 0) {
            BigButton(
                text = stringResource(R.string.send_now, waiting),
                onClick = {
                    scope.launch { if (repo.sendQueued() == 0) Toast.makeText(context, noSignal, Toast.LENGTH_LONG).show() }
                },
                icon = Icons.Rounded.CloudUpload,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun ReadingRow(m: Meter, r: Reading) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Card)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MeterTypeIcon(m.type, size = 42.dp)
        Column(Modifier.weight(1f)) {
            Text(m.number, fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                listOfNotNull(r.value?.let(::formatReading), formatTime(r.capturedAt)).joinToString(" · "),
                color = AppColors.SubInk,
            )
        }
        StateChip(r.state, showLabel = false)
    }
}

@Composable
fun SummaryScreen(onBack: () -> Unit) {
    val repo = AppGraph.repository
    val meters by repo.meters.collectAsStateWithLifecycle()
    val readings by repo.readings.collectAsStateWithLifecycle()
    val r = remember(meters, readings) { Reconciliation.from(meters, readings) }
    val photosWaiting by repo.photosWaiting.collectAsStateWithLifecycle()
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val noSignal = stringResource(R.string.no_signal_now)

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.summary_title), stringResource(R.string.speak_summary), onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (photosWaiting > 0) {
                Banner(stringResource(R.string.summary_photos_waiting, photosWaiting), Icons.Rounded.PhotoCamera, AppColors.Queued, AppColors.QueuedTint)
            }
            if (r.allUploaded) {
                Banner(stringResource(R.string.summary_all_uploaded), Icons.Rounded.CloudDone, AppColors.Ok, AppColors.OkTint)
            } else {
                Banner(stringResource(R.string.summary_not_uploaded, r.waiting), Icons.Rounded.CloudUpload, AppColors.Queued, AppColors.QueuedTint)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountTile(r.meters, stringResource(R.string.summary_meters), Modifier.weight(1f))
                CountTile(r.read, stringResource(R.string.summary_read), Modifier.weight(1f))
                CountTile(r.uploaded, stringResource(R.string.summary_uploaded), Modifier.weight(1f), mismatch = !r.allUploaded)
            }
            val chartDescription = stringResource(
                R.string.summary_chart, r.meters, r.accepted, r.checking, r.readAgain, r.revisit, r.waiting, r.notRead,
            )
            val segments = listOf(
                r.accepted to AppColors.Ok, r.checking to AppColors.Warn, r.readAgain to AppColors.Bad,
                r.revisit to AppColors.Taupe, r.waiting to AppColors.Queued, r.notRead to AppColors.Line,
            ).filter { it.first > 0 }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .semantics { contentDescription = chartDescription },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                segments.forEach { (count, color) ->
                    Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(color))
                }
            }
            Column {
                LegendRow(Icons.Rounded.Check, stringResource(R.string.summary_accepted), r.accepted, AppColors.Ok, AppColors.OkTint)
                LegendRow(Icons.Rounded.Warning, stringResource(R.string.state_checking), r.checking, AppColors.Warn, AppColors.WarnTint)
                LegendRow(Icons.Rounded.Close, stringResource(R.string.state_read_again), r.readAgain, AppColors.Bad, AppColors.BadTint)
                if (r.revisit > 0) LegendRow(Icons.Rounded.Refresh, stringResource(R.string.state_revisit), r.revisit, AppColors.Taupe, AppColors.TaupeTint)
                LegendRow(Icons.Rounded.CloudUpload, stringResource(R.string.summary_waiting), r.waiting, AppColors.Queued, AppColors.QueuedTint)
                LegendRow(Icons.Rounded.RadioButtonUnchecked, stringResource(R.string.summary_not_read), r.notRead, AppColors.SubInk, AppColors.Background, last = true)
            }
            ZoneTable(r.zones)
            r.lastUpload?.let {
                Text(stringResource(R.string.summary_last_upload, formatTime(it)), color = AppColors.SubInk)
            }
        }
        if (!r.allUploaded || photosWaiting > 0) {
            BigButton(
                text = if (sending) stringResource(R.string.sending) else stringResource(R.string.upload_now, r.waiting + photosWaiting),
                onClick = {
                    sending = true
                    scope.launch {
                        if (repo.sendQueued() == 0) Toast.makeText(context, noSignal, Toast.LENGTH_LONG).show()
                        sending = false
                    }
                },
                enabled = !sending,
                icon = Icons.Rounded.CloudUpload,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun CountTile(value: Int, label: String, modifier: Modifier, mismatch: Boolean = false) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (mismatch) AppColors.QueuedTint else AppColors.Card)
            .border(1.5.dp, if (mismatch) AppColors.Queued else AppColors.Line, RoundedCornerShape(16.dp))
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$value", fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = if (mismatch) AppColors.Queued else AppColors.Ink)
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (mismatch) AppColors.Queued else AppColors.SubInk)
    }
}

@Composable
private fun LegendRow(icon: ImageVector, label: String, value: Int, tint: Color, background: Color, last: Boolean = false) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(background),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("$value", fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        if (!last) HorizontalDivider(color = AppColors.Line)
    }
}

@Composable
private fun ZoneTable(zones: List<ZoneReconciliation>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Card)
            .padding(12.dp),
    ) {
        Row {
            listOf(R.string.summary_zone, R.string.summary_meters, R.string.summary_read, R.string.summary_uploaded).forEachIndexed { i, label ->
                Text(
                    stringResource(label).uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = AppColors.SubInk,
                    textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        zones.forEach { z ->
            HorizontalDivider(color = AppColors.Line, modifier = Modifier.padding(vertical = 6.dp))
            Row {
                listOf(z.zoneCode, "${z.meters}", "${z.read}", "${z.uploaded}").forEachIndexed { i, value ->
                    val missing = i == 3 && z.waiting > 0
                    Text(
                        value,
                        fontFamily = NumberFont,
                        fontWeight = if (missing) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 18.sp,
                        color = if (missing) AppColors.Queued else AppColors.Ink,
                        textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
