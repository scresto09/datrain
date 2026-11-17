package com.scresto.datrain.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.scresto.datrain.data.DataMode

private class ModeText(val name: String, val effect: String, val volume: String)

private val MODES = mapOf(
    DataMode.REACTIVE to ModeText(
        "Toujours à jour",
        "Datrain recherche régulièrement les dernières informations pour vous fournir les horaires et les retards les plus récents.",
        "Environ 6 Mo par jour.",
    ),
    DataMode.BALANCED to ModeText(
        "Bon équilibre",
        "Les informations sont actualisées toutes les 6 heures. Les retards sont également surveillés à l'approche de votre départ.",
        "Environ 2 Mo par jour.",
    ),
    DataMode.SAVER to ModeText(
        "Économiser mes données",
        "Les horaires sont actualisés uniquement lorsque vous êtes connecté au Wi-Fi.",
        "Environ 1 Mo par jour.",
    ),
)

internal fun dataModeName(mode: DataMode): String = MODES.getValue(mode).name

/** The three modes, each with what it does; then what holds for all of them. */
@Composable
internal fun DataModeChoice(selected: DataMode, onSelect: (DataMode) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DataMode.entries.forEach { mode ->
            val text = MODES.getValue(mode)
            Row(
                Modifier.fillMaxWidth().selectable(selected = mode == selected, role = Role.RadioButton) { onSelect(mode) },
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = mode == selected, onClick = null)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(text.name, fontWeight = FontWeight.Medium)
                    Text(text.effect, style = MaterialTheme.typography.bodySmall)
                    Text(text.volume, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("En Wi-Fi, les informations sont toujours actualisées, quel que soit le mode choisi.", style = MaterialTheme.typography.bodySmall)
        Text(
            "Consommations indicatives, variables selon le nombre de trains suivis et votre utilisation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Before the choice, so that it is read whatever the screen size. */
@Composable
internal fun MobileDataWarning() {
    Text(
        "Attention : en dehors du Wi-Fi, Datrain utilise la connexion Internet mobile de votre téléphone. " +
            "Selon votre forfait, cette consommation peut être décomptée de votre enveloppe Internet ou entraîner des frais supplémentaires.",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
    )
}

/** At the first launch, and after the update that brought the modes: closed only by choosing. */
@Composable
internal fun DataModeDialog(onChosen: (DataMode) -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(DataMode.REACTIVE) }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Utilisation des données mobiles") },
        text = {
            val scroll = rememberScrollState()
            Box {
                Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Lorsque vous n'êtes pas connecté au Wi-Fi, comment souhaitez-vous que Datrain actualise les informations sur vos trains ?")
                MobileDataWarning()
                DataModeChoice(chosen) { chosen = it }
                Text(
                    "Vous pourrez modifier ce réglage à tout moment en touchant le logo Datrain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
                // More below on a small screen: said, rather than left to be guessed.
                if (scroll.canScrollForward) {
                    val background = AlertDialogDefaults.containerColor
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp)
                            .background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background))),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Text("▼", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChosen(chosen) }) { Text("Valider") } },
    )
}
