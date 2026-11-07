package com.scresto.datrain.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.scresto.datrain.data.FavoriteTrain
import com.scresto.datrain.data.Trip

/** For a train found by its number: the traveller picks where they board and where they get off. */
@Composable
internal fun FavoriteStationsDialog(trip: Trip, onDismiss: () -> Unit, onPicked: (FavoriteTrain) -> Unit) {
    val stops = trip.plannedStops.filterNot { it.removed }.mapNotNull { stop ->
        trip.plannedStations[stop.uic]?.name?.let { stop.uic to it }
    }
    var from by rememberSaveable { mutableIntStateOf(0) }
    var to by rememberSaveable { mutableIntStateOf(stops.lastIndex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter aux favoris") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${trip.product} ${trip.number}", style = MaterialTheme.typography.bodyMedium)
                StopChoice("Montée", stops.map { it.second }, from, enabled = { it < to }) { from = it }
                StopChoice("Descente", stops.map { it.second }, to, enabled = { it > from }) { to = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = stops.size >= 2 && from < to,
                onClick = {
                    val (fromUic, fromName) = stops[from]
                    val (toUic, toName) = stops[to]
                    onPicked(FavoriteTrain(trip.number, trip.product, fromName, toName, fromUic.toIntOrNull() ?: 0, toUic.toIntOrNull() ?: 0))
                },
            ) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun StopChoice(label: String, names: List<String>, selected: Int, enabled: (Int) -> Boolean, onSelect: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(names.getOrElse(selected) { "" }, maxLines = 1)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                names.forEachIndexed { index, name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        enabled = enabled(index),
                        onClick = {
                            onSelect(index)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
