package com.scresto.datrain.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.scresto.datrain.data.FavoriteTrain
import java.time.DayOfWeek

private val LETTERS = listOf("L", "M", "M", "J", "V", "S", "D")
private val SHORT = listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")

/** "lun. mar.", or "Tous les jours" when the favorite counts every day it runs. */
internal fun daysLabel(days: List<Int>): String =
    if (days.isEmpty()) "Tous les jours" else days.sorted().joinToString(" ") { SHORT[it - 1] }

/**
 * The days the traveller takes [favorite]. Days it does not run are greyed out. Choosing every day it runs
 * saves no restriction, so that a train later running on more days still counts.
 */
@Composable
internal fun FavoriteDaysDialog(
    favorite: FavoriteTrain,
    running: Set<DayOfWeek>,
    adding: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
) {
    val runs = running.ifEmpty { DayOfWeek.entries.toSet() }
    var chosen by remember {
        mutableStateOf(if (favorite.days.isEmpty()) runs.map { it.value }.toSet() else favorite.days.toSet())
    }
    val useful = chosen.filter { DayOfWeek.of(it) in runs }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adding) "Ajouter aux favoris" else "Jours suivis") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    favorite.legs.joinToString(" + ") { "${it.product} ${it.number}" } + " · ${favorite.fromName} → ${favorite.lastToName}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Quels jours le prenez-vous ?")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DayOfWeek.entries.forEach { day ->
                        val runsThatDay = day in runs
                        DayToggle(LETTERS[day.value - 1], selected = runsThatDay && day.value in chosen, enabled = runsThatDay) {
                            chosen = if (day.value in chosen) chosen - day.value else chosen + day.value
                        }
                    }
                }
                if (runs.size < 7) {
                    Text("Les jours grisés, ce train ne circule pas.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = useful.isNotEmpty(),
                onClick = { onConfirm(if (useful.size == runs.size) emptyList() else useful.sorted()) },
            ) { Text(if (adding) "Ajouter" else "OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Seven of these must fit a dialog on a small phone: smaller than a chip. */
@Composable
private fun DayToggle(letter: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (selected) colors.primary else colors.surfaceVariant,
        contentColor = when {
            selected -> colors.onPrimary
            enabled -> colors.onSurfaceVariant
            else -> colors.outlineVariant
        },
        modifier = Modifier.size(34.dp).clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(letter, fontWeight = FontWeight.SemiBold) }
    }
}
