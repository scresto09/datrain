package com.scresto.datrain.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scresto.datrain.R
import com.scresto.datrain.data.JourneyPlanner
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.RecentJourney
import com.scresto.datrain.data.RecentStation
import com.scresto.datrain.data.RecentTrain
import com.scresto.datrain.data.Station
import com.scresto.datrain.data.TimetableState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

@Composable
internal fun TimetableStatus(state: TimetableState, onRetry: () -> Unit) {
    when (state) {
        TimetableState.Loading -> Unit
        TimetableState.Downloading -> Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DatrainLogo()
            TrainLoadingLine()
            Text("Téléchargement des horaires (environ 1 Mo)…", style = MaterialTheme.typography.bodySmall)
        }
        is TimetableState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry) { Text("Réessayer") }
        }
        is TimetableState.Ready -> state.info.generatedAt?.let {
            Text(
                "Horaires du ${it.atZoneSameInstant(PARIS).toLocalDate().format(SHORT_DAY_FORMAT)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun SearchForm(state: UiState, vm: SearchViewModel) {
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val planner = (state.timetable as? TimetableState.Ready)?.planner
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StationField("Gare de départ", state.fromText, state.from, planner, state.recent.stations, vm::setFromText, vm::selectFrom, vm::forgetStation)
                StationField("Gare d'arrivée", state.toText, state.to, planner, state.recent.stations, vm::setToText, vm::selectTo, vm::forgetStation)
            }
            IconButton(onClick = vm::swapStations) {
                Icon(painterResource(R.drawable.ic_swap), contentDescription = "Inverser les gares")
            }
        }
        if (state.recent.journeys.isNotEmpty()) {
            RecentJourneys(state.recent.journeys, vm::selectRecentJourney, vm::forgetJourney)
        }
        if (state.sameStation) {
            Text("Les deux gares sont identiques.", color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { pickDate = true },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(formatDay(state.date), maxLines = 1)
            }
            OutlinedButton(
                onClick = { pickTime = true },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("${if (state.arriveBy) "Arrivée" else "Départ"} ${state.time.format(TIME_FORMAT)}", maxLines = 1)
            }
            FilledIconButton(
                onClick = {
                    focusManager.clearFocus() // Closes the keyboard, which would hide the results.
                    vm.search()
                },
                enabled = state.canSearch,
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Rechercher")
            }
        }
    }

    if (pickDate) {
        DateDialog(state.date, onDismiss = { pickDate = false }) {
            vm.setDate(it)
            pickDate = false
        }
    }
    if (pickTime) {
        TimeDialog(state.time, state.arriveBy, onDismiss = { pickTime = false }) { time, arriveBy ->
            vm.setTime(time, arriveBy)
            pickTime = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationField(
    label: String,
    text: String,
    selected: Station?,
    planner: JourneyPlanner?,
    recentStations: List<RecentStation>,
    onTextChange: (String) -> Unit,
    onSelected: (Station) -> Unit,
    onForget: (RecentStation) -> Unit,
) {
    val query = text.trim()
    val suggestions = remember(query, selected, planner) {
        if (selected == null && query.length >= 2) planner?.searchStations(query).orEmpty() else emptyList()
    }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val recents = if (focused && selected == null && query.isEmpty()) recentStations else emptyList()
    val expanded = (suggestions.isNotEmpty() || recents.isNotEmpty()) && !dismissed
    val focusManager = LocalFocusManager.current

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { dismissed = !it }) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                dismissed = false
                onTextChange(it)
            },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = if (text.isEmpty()) {
                null
            } else {
                {
                    IconButton(
                        onClick = {
                            onTextChange("")
                            dismissed = false
                            focusRequester.requestFocus()
                        },
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Effacer $label")
                    }
                }
            },
            isError = planner != null && selected == null && query.length >= 2 && suggestions.isEmpty(),
            supportingText = if (planner != null && selected == null && query.length >= 2 && suggestions.isEmpty()) {
                { Text("Aucune gare à ce nom dans les horaires.") }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .menuAnchor(MenuAnchorType.PrimaryEditable)
                .onFocusChanged {
                    if (it.isFocused && !focused) dismissed = false
                    focused = it.isFocused
                },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { dismissed = true }) {
            recents.forEach { station ->
                RecentStationItem(
                    name = station.name,
                    onClick = {
                        focusManager.clearFocus()
                        onSelected(station.toStation())
                    },
                    onLongClick = { onForget(station) },
                )
            }
            suggestions.forEach { station ->
                DropdownMenuItem(
                    text = { Text(station.name) },
                    onClick = {
                        focusManager.clearFocus()
                        onSelected(station)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentStationItem(name: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onLongClickLabel = "Retirer de la liste", onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_history), contentDescription = null)
        Text(name, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentJourneys(
    journeys: List<RecentJourney>,
    onSelected: (RecentJourney) -> Unit,
    onForget: (RecentJourney) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        journeys.forEach { journey ->
            Surface(
                shape = MaterialTheme.shapes.small,
                tonalElevation = 2.dp,
                modifier = Modifier.widthIn(max = 280.dp),
            ) {
                Text(
                    "${journey.from.name} → ${journey.to.name}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .combinedClickable(
                            onLongClickLabel = "Retirer de la liste",
                            onLongClick = { onForget(journey) },
                            onClick = {
                                focusManager.clearFocus()
                                onSelected(journey)
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecentTrainsMenu(
    trains: List<RecentTrain>,
    onSelected: (RecentTrain) -> Unit,
    onForget: (RecentTrain) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painterResource(R.drawable.ic_train),
                contentDescription = "Trains consultés",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Trains consultés",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            trains.forEach { train ->
                val departure = LocalDateTime.parse(train.departure).format(TIME_FORMAT)
                Row(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .fillMaxWidth()
                        .combinedClickable(
                            onLongClickLabel = "Retirer de la liste",
                            onLongClick = { onForget(train) },
                            onClick = {
                                open = false
                                onSelected(train)
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_train),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "${train.product} ${train.number} · ${train.fromName} $departure",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateDialog(date: LocalDate, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    // DatePicker works in UTC milliseconds: any other zone would shift the day.
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let {
                    onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    ) {
        DatePicker(state = pickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    time: LocalTime,
    arriveBy: Boolean,
    onDismiss: () -> Unit,
    onPicked: (LocalTime, Boolean) -> Unit,
) {
    val pickerState = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
    var arrival by rememberSaveable { mutableStateOf(arriveBy) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPicked(LocalTime.of(pickerState.hour, pickerState.minute), arrival) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Départ" to false, "Arrivée" to true).forEachIndexed { index, (label, value) ->
                        SegmentedButton(
                            selected = arrival == value,
                            onClick = { arrival = value },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(label) }
                    }
                }
                TimePicker(state = pickerState)
            }
        },
    )
}
