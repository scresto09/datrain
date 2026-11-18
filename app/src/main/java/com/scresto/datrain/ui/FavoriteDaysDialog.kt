package com.scresto.datrain.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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

private val RUN_DAY = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMM 'à' HH:mm", java.util.Locale.FRENCH)

/**
 * The days the traveller takes [favorite]. Days it does not run are greyed out. Choosing every day it runs
 * saves no restriction, so that a train later running on more days still counts. When [adding] a train
 * seen on a given day, [temporaryRun] is that run: it can be followed alone, first when [preferTemporary].
 */
@Composable
internal fun FavoriteDaysDialog(
    favorite: FavoriteTrain,
    running: Set<DayOfWeek>,
    adding: Boolean,
    onDismiss: () -> Unit,
    temporaryRun: java.time.LocalDateTime? = null,
    preferTemporary: Boolean = false,
    onConfirm: (days: List<Int>, temporary: Boolean) -> Unit,
) {
    val runs = running.ifEmpty { DayOfWeek.entries.toSet() }
    var chosen by remember {
        mutableStateOf(if (favorite.days.isEmpty()) runs.map { it.value }.toSet() else favorite.days.toSet())
    }
    var temporary by remember { mutableStateOf(adding && temporaryRun != null && preferTemporary) }
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
                if (adding && temporaryRun != null) {
                    Column(Modifier.selectableGroup()) {
                        ChoiceRow("Suivi temporaire", temporary) { temporary = true }
                        if (temporary) {
                            Text(
                                temporaryRun.format(RUN_DAY).replaceFirstChar { it.uppercase() } +
                                    ". Retiré automatiquement 30 minutes après l'arrivée.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 40.dp),
                            )
                        }
                        ChoiceRow("Régulièrement", !temporary) { temporary = false }
                    }
                }
                if (!temporary) {
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = temporary || useful.isNotEmpty(),
                onClick = { onConfirm(if (temporary || useful.size == runs.size) emptyList() else useful.sorted(), temporary) },
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

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 16.dp))
    }
}
