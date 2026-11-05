package com.scresto.datrain.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.scresto.datrain.data.RideLeg
import java.time.format.DateTimeFormatter

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainSearchScreen(vm: SearchViewModel, state: UiState) {
    val search = state.trainSearch
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var letters by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    BackHandler { vm.showTrainSearch(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Numéro de train") },
                navigationIcon = {
                    IconButton(onClick = { vm.showTrainSearch(false) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
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
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = search.query,
                        onValueChange = vm::setTrainQuery,
                        label = { Text("Numéro du train") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (letters) KeyboardType.Ascii else KeyboardType.Number,
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(onSearch = { vm.saveTrainQuery() }),
                        trailingIcon = {
                            TextButton(onClick = { letters = !letters }) { Text(if (letters) "123" else "ABC") }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                    if (state.recent.numbers.isNotEmpty()) {
                        RecentNumbers(state.recent.numbers, vm::setTrainQuery, vm::forgetNumber)
                    }
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(formatDay(search.date))
                    }
                }
            }
            items(search.results, key = { "${it.trip.number}-${it.trip.departure}" }) { ride ->
                TrainResult(ride) { vm.openSearchedTrain(ride) }
            }
            if (search.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        TrainLoadingLine()
                    }
                }
            } else if (search.query.isNotBlank() && search.results.isEmpty()) {
                item { Text("Aucun train à ce numéro ce jour-là.") }
            }
        }
    }

    if (pickDate) {
        DateDialog(search.date, onDismiss = { pickDate = false }) {
            vm.setTrainSearchDate(it)
            pickDate = false
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentNumbers(numbers: List<String>, onSelected: (String) -> Unit, onForget: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        numbers.forEach { number ->
            Surface(shape = MaterialTheme.shapes.small, tonalElevation = 2.dp) {
                Text(
                    number,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .combinedClickable(
                            onLongClickLabel = "Retirer de la liste",
                            onLongClick = { onForget(number) },
                            onClick = { onSelected(number) },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun TrainResult(ride: RideLeg, onClick: () -> Unit) {
    val trip = ride.trip
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${trip.product} ${trip.number}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (trip.cancelled) {
                    Text("Supprimé", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            }
            // A cancelled train is struck through, as everywhere in the app.
            val struck = if (trip.cancelled) TextDecoration.LineThrough else null
            Text("${trip.departure.format(CLOCK)} ${ride.fromName}", textDecoration = struck)
            Text("${trip.arrival.format(CLOCK)} ${ride.toName}", textDecoration = struck)
        }
    }
}
