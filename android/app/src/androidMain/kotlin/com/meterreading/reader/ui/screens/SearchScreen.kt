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
import com.meterreading.reader.util.rememberVoiceInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable

private const val MAX_QUERY = 20

/**
 * Find a building or meter by a few digits, by voice, or with letters (ABC) for codes like
 * 1499-W1. With no text it lists everything in scope, so it doubles as a filtered browse.
 */
@Composable
fun SearchScreen(
    zoneCode: String?,
    initialText: String?,
    onProperty: (String) -> Unit,
    onMeter: (String) -> Unit,
    onBack: () -> Unit,
) {
    val repo = AppGraph.repository
    val meters by repo.meters.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(initialText?.let(::cleanSpoken).orEmpty()) }
    var done by rememberSaveable { mutableStateOf(DoneFilter.ALL) }
    var type by rememberSaveable { mutableStateOf<MeterType?>(null) }
    var letters by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val voiceMissing = stringResource(R.string.search_voice_missing)
    val placeholder = zoneCode?.let { stringResource(R.string.search_in_zone, it) } ?: stringResource(R.string.search_placeholder)
    val voice = rememberVoiceInput(placeholder) { text = cleanSpoken(it) }

    val all = remember(meters) { repo.allProperties(meters) }
    val results = remember(all, text, done, type, zoneCode) { Search.run(SearchQuery(text, done, type, zoneCode), all) }
    val matched = remember(all, text, type, zoneCode) { Search.run(SearchQuery(text, DoneFilter.ALL, type, zoneCode), all).properties }
    val toRead = matched.count { p -> p.meters.any { it.state.canCapture } }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(placeholder, stringResource(R.string.speak_search), onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (letters) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.uppercase().take(MAX_QUERY) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = NumberFont),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Search,
                    ),
                    leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(28.dp)) },
                    trailingIcon = {
                        TextButton(onClick = { letters = false }) {
                            Text(stringResource(R.string.search_numbers), style = MaterialTheme.typography.labelLarge)
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
            } else {
                SearchField(
                    text = text,
                    placeholder = placeholder,
                    onClick = {},
                    onMic = { if (!voice()) Toast.makeText(context, voiceMissing, Toast.LENGTH_LONG).show() },
                    active = true,
                )
            }
            FilterRow(done, { done = it }, type, { type = it }, toRead = toRead, doneCount = matched.size - toRead, total = matched.size)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (results.isEmpty) {
                item { Banner(stringResource(R.string.search_nothing), Icons.Rounded.SearchOff, AppColors.SubInk, AppColors.Card) }
            }
            if (results.properties.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.search_buildings)) }
                items(results.properties, key = { "p-" + it.property.code }) { p ->
                    PropertyCard(p, query = text, onClick = { onProperty(p.property.code) }, showZone = true)
                }
            }
            if (results.meters.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.search_meters)) }
                items(results.meters, key = { "m-" + it.id }) { m ->
                    MeterCard(m, query = text, highlighted = false, onClick = { onMeter(m.id) })
                }
            }
        }
        if (!letters) {
            SearchPad(
                onKey = { if (text.length < MAX_QUERY) text += it },
                onDelete = { text = text.dropLast(1) },
                onClear = { text = "" },
                onLetters = { letters = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = AppColors.SubInk,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Keeps what a reader can say about a code: "fourteen ninety nine W1" arrives as "1499 W1". */
private fun cleanSpoken(spoken: String): String =
    spoken.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(MAX_QUERY)
