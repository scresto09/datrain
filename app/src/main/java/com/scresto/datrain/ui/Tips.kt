package com.scresto.datrain.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scresto.datrain.R

/** A tip shown once, where it applies: closed with its cross, never shown again unless the presentation is. */
@Composable
internal fun Tip(text: String, onDismiss: () -> Unit, action: Pair<String, () -> Unit>? = null) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                action?.let { (label, onClick) ->
                    Text(
                        "$label ›",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp).clickable(onClick = onClick),
                    )
                }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Fermer l'astuce") }
        }
    }
}

/** The tips, each shown once. */
internal object Tips {
    const val STAR = "star"
    const val DAYS = "days"
    const val STOPS = "stops"
    const val ALTERNATIVE = "alternative"
    const val WIDGET = "widget"
    const val WIDGET_OFFER = "widgetOffer"
}
