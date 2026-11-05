package com.scresto.datrain.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scresto.datrain.R
import com.scresto.datrain.data.Itinerary
import com.scresto.datrain.data.JourneyPlanner
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.RideLeg
import com.scresto.datrain.data.ServiceAlert
import com.scresto.datrain.data.Station
import com.scresto.datrain.data.TimetableState
import com.scresto.datrain.data.TransferLeg
import com.scresto.datrain.data.Trip
import kotlinx.coroutines.Dispatchers
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.FRENCH)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
private val SHORT_DAY_FORMAT = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)
/** Below this, a change is flagged as risky. */
private const val TIGHT_CONNECTION_MINUTES = 5L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatrainApp(vm: SearchViewModel = viewModel()) {
    // Immediate: a text field receiving its value late can duplicate or lose letters.
    val state by vm.state.collectAsState(Dispatchers.Main.immediate)

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        state.trackedTrip?.let { trip ->
            TrainMapScreen(trip, onBack = { vm.track(null) })
            return@MaterialTheme
        }
        if (state.showSources) {
            SourcesScreen(onBack = { vm.showSources(false) })
            return@MaterialTheme
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Datrain") },
                    actions = {
                        IconButton(onClick = { vm.showSources(true) }) {
                            Icon(Icons.Filled.Info, contentDescription = "Sources des données")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { TimetableStatus(state.timetable, vm::retryDownload) }
                item { SearchForm(state, vm) }
                state.error?.let { error ->
                    item { Text(error, color = MaterialTheme.colorScheme.error) }
                }
                state.suburbanMissingFrom?.let {
                    val until = (state.timetable as TimetableState.Ready).info.suburbanUntil!!
                    item {
                        Text("RER et Transilien : horaires connus jusqu'au ${until.format(SHORT_DAY_FORMAT)}. Ces trains ne sont pas proposés après cette date.")
                    }
                }
                if (state.realtimeUnavailable) {
                    item { Text("Temps réel indisponible : seuls les horaires prévus sont affichés.") }
                }
                state.itineraries.groupBy { it.departure.toLocalDate() }.forEach { (day, itineraries) ->
                    item(key = "day-$day") {
                        Text(
                            day.format(DAY_FORMAT).replaceFirstChar { it.uppercase(Locale.FRENCH) },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(itineraries, key = { it.id }) { itinerary ->
                        ItineraryCard(
                            itinerary = itinerary,
                            alerts = state.alerts[itinerary.id],
                            alertsFailed = itinerary.id in state.alertsFailed,
                            onExpand = { vm.loadAlerts(itinerary) },
                            onTrack = vm::track,
                        )
                    }
                }
                if (state.isLoading) {
                    item { CenteredProgress() }
                }
                if (state.hasSearched && !state.isLoading && state.error == null && state.itineraries.isEmpty()) {
                    item { Text("Aucun trajet ce jour-là à partir de cette heure.") }
                }
                if (state.nextDay != null && !state.isLoading) {
                    item {
                        OutlinedButton(onClick = vm::loadNextDay, modifier = Modifier.fillMaxWidth()) {
                            Text("Voir le ${state.nextDay!!.format(DATE_FORMAT)}")
                        }
                    }
                }
                item {
                    Text(
                        "Temps de changement estimés : vérifie avant de voyager.\nDonnées SNCF et Île-de-France Mobilités, via Train Nomade pour le temps réel. Détail avec le bouton ⓘ.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimetableStatus(state: TimetableState, onRetry: () -> Unit) {
    when (state) {
        TimetableState.Loading -> Unit
        TimetableState.Downloading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text("Téléchargement des horaires (environ 1 Mo)…")
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
private fun CenteredProgress() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SearchForm(state: UiState, vm: SearchViewModel) {
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val planner = (state.timetable as? TimetableState.Ready)?.planner
        StationField("Gare de départ", state.fromText, state.from, planner, vm::setFromText, vm::selectFrom)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            IconButton(onClick = vm::swapStations) {
                Icon(painterResource(R.drawable.ic_swap), contentDescription = "Inverser les gares")
            }
        }
        StationField("Gare d'arrivée", state.toText, state.to, planner, vm::setToText, vm::selectTo)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(2f)) {
                Text(state.date.format(DATE_FORMAT))
            }
            OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f)) {
                Text(state.time.format(TIME_FORMAT))
            }
        }
        if (state.sameStation) {
            Text("Les deux gares sont identiques.", color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                focusManager.clearFocus() // Closes the keyboard, which would hide the results.
                vm.search()
            },
            enabled = state.canSearch,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Rechercher")
        }
    }

    if (pickDate) {
        DateDialog(state.date, onDismiss = { pickDate = false }) {
            vm.setDate(it)
            pickDate = false
        }
    }
    if (pickTime) {
        TimeDialog(state.time, onDismiss = { pickTime = false }) {
            vm.setTime(it)
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
    onTextChange: (String) -> Unit,
    onSelected: (Station) -> Unit,
) {
    val query = text.trim()
    val suggestions = remember(query, selected, planner) {
        if (selected == null && query.length >= 2) planner?.searchStations(query).orEmpty() else emptyList()
    }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val expanded = suggestions.isNotEmpty() && !dismissed
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
            isError = planner != null && selected == null && query.length >= 2 && suggestions.isEmpty(),
            supportingText = if (planner != null && selected == null && query.length >= 2 && suggestions.isEmpty()) {
                { Text("Aucune gare à ce nom dans les horaires.") }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { dismissed = true }) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(date: LocalDate, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
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
private fun TimeDialog(time: LocalTime, onDismiss: () -> Unit, onPicked: (LocalTime) -> Unit) {
    val pickerState = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPicked(LocalTime.of(pickerState.hour, pickerState.minute)) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        text = { TimePicker(state = pickerState) },
    )
}

@Composable
private fun ItineraryCard(
    itinerary: Itinerary,
    alerts: List<ServiceAlert>?,
    alertsFailed: Boolean,
    onExpand: () -> Unit,
    onTrack: (Trip) -> Unit,
) {
    var expanded by rememberSaveable(itinerary.id) { mutableStateOf(false) }
    val atRisk = itinerary.hasConnectionAtRisk(TIGHT_CONNECTION_MINUTES)
    Card(
        Modifier.fillMaxWidth().clickable {
            expanded = !expanded
            if (expanded) onExpand()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${itinerary.departure.format(TIME_FORMAT)} → ${itinerary.arrival.format(TIME_FORMAT)}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(formatDuration(Duration.between(itinerary.departure, itinerary.arrival)))
            }
            val days = java.time.temporal.ChronoUnit.DAYS.between(itinerary.departure.toLocalDate(), itinerary.arrival.toLocalDate())
            if (days > 0) Text("Arrivée à J+$days", style = MaterialTheme.typography.bodySmall)
            Text(
                when (itinerary.changes) {
                    0 -> "Direct"
                    1 -> "1 changement"
                    else -> "${itinerary.changes} changements"
                } + " · " + itinerary.rides.joinToString(", ") { it.trip.product },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (itinerary.cancelled) {
                Text("Un train est supprimé", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            } else if (atRisk) {
                Text("Correspondance menacée", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ItineraryDetail(itinerary, onTrack)
                AlertsSection(alerts, alertsFailed)
            }
        }
    }
}

@Composable
private fun ItineraryDetail(itinerary: Itinerary, onTrack: (Trip) -> Unit) {
    val now = LocalDateTime.now(PARIS)
    itinerary.legs.forEach { leg ->
        when (leg) {
            is TransferLeg -> Text(
                if (leg.isEstimate) {
                    "Changement de gare : ${leg.toName}, environ ${leg.seconds / 60} min en métro ou RER (estimation)"
                } else {
                    "À pied jusqu'à ${leg.toName}, environ ${leg.seconds / 60} min"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            is RideLeg -> Column {
                val trip = leg.trip
                Text("${trip.product} ${trip.number}", fontWeight = FontWeight.SemiBold)
                Text("${trip.departure.format(TIME_FORMAT)} ${leg.fromName}")
                Text("${trip.arrival.format(TIME_FORMAT)} ${leg.toName}")
                Text("Direction ${trip.destination}", style = MaterialTheme.typography.bodySmall)
                trip.platform?.let { Text("Voie $it", style = MaterialTheme.typography.bodyMedium) }
                realtimeLabel(trip)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
                if (trip.isTrackableAt(now)) {
                    TextButton(onClick = { onTrack(trip) }, contentPadding = PaddingValues(0.dp)) {
                        Text("Voir sur la carte")
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertsSection(alerts: List<ServiceAlert>?, failed: Boolean) {
    when {
        failed -> Text("Informations de perturbation indisponibles pour le moment.", style = MaterialTheme.typography.bodySmall)
        alerts == null -> Text("Recherche d'informations sur ce trajet…", style = MaterialTheme.typography.bodySmall)
        alerts.isEmpty() -> Text("Aucune information signalée sur ce trajet.", style = MaterialTheme.typography.bodySmall)
        else -> {
            var open by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
                val count = alerts.size
                val label = if (count == 1) "1 information sur ce trajet" else "$count informations sur ce trajet"
                Text(if (open) "Masquer" else label)
            }
            if (open) {
                alerts.forEach { alert ->
                    Text(alert.title, fontWeight = FontWeight.SemiBold)
                    if (alert.description.isNotEmpty()) Text(alert.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun realtimeLabel(trip: Trip): String? {
    if (trip.cancelled) return "Supprimé"
    val parts = buildList {
        if (trip.departureDelayMinutes > 0) add("Départ +${trip.departureDelayMinutes} min")
        if (trip.arrivalDelayMinutes > 0) add("Arrivée +${trip.arrivalDelayMinutes} min")
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

private fun formatDuration(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    return if (hours > 0) "${hours}h${minutes.toString().padStart(2, '0')}" else "$minutes min"
}
