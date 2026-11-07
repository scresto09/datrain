package com.scresto.datrain.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.scresto.datrain.BuildConfig

private const val HOMEPAGE = "https://scresto.pages-perso.free.fr/datrain"
private const val HELP_PAGE = "https://scresto.pages-perso.free.fr/datrain/help.html"
private const val PRIVACY_PAGE = "https://scresto.pages-perso.free.fr/datrain/privacy.html"

/** A rounded rectangle with a notch on each side, where the ticket's stub would tear off. */
private class TicketShape(private val notchY: Float, private val notchRadius: Float, private val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val ticket = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner))) }
        val notches = Path().apply {
            addOval(Rect(Offset(0f, notchY), notchRadius))
            addOval(Rect(Offset(size.width, notchY), notchRadius))
        }
        return Outline.Generic(Path.combine(PathOperation.Difference, ticket, notches))
    }
}

@Composable
private fun AboutLink(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text("›", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun AboutDialog(
    notifications: Boolean,
    onNotifications: (Boolean) -> Unit,
    onSources: () -> Unit,
    onTestNotification: () -> Unit,
    hasFavorites: Boolean,
    onDismiss: () -> Unit,
) {
    var stubHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val shape = with(density) { TicketShape(stubHeight.toFloat(), 9.dp.toPx(), 16.dp.toPx()) }
    val rule = MaterialTheme.colorScheme.outlineVariant
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = shape, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    Modifier.fillMaxWidth().onSizeChanged { stubHeight = it.height }.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    DatrainLogo()
                    Text(
                        "BILLET\nN° ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Canvas(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp)) {
                    drawLine(
                        rule,
                        Offset(0f, size.height / 2),
                        Offset(size.width, size.height / 2),
                        size.height,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                    )
                }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Notifications", fontWeight = FontWeight.Medium)
                            Text("Retards, suppressions et messages de vos trains favoris", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = notifications, onCheckedChange = onNotifications)
                    }
                    if (BuildConfig.DEBUG) {
                        TextButton(onClick = onTestNotification, enabled = hasFavorites, contentPadding = PaddingValues(0.dp)) {
                            Text(if (hasFavorites) "Tester les notifications" else "Tester les notifications (ajoutez un favori)")
                        }
                    }
                    HorizontalDivider()
                }
                val uriHandler = LocalUriHandler.current
                AboutLink("Aide en ligne") { uriHandler.openUri(HELP_PAGE) }
                AboutLink("Créé par Sylvain Cresto") { uriHandler.openUri(HOMEPAGE) }
                AboutLink("Confidentialité") { uriHandler.openUri(PRIVACY_PAGE) }
                AboutLink("Informations légales", onSources)
                Text(
                    "Application indépendante, sans lien avec la SNCF ni Île-de-France Mobilités.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                )
            }
        }
    }
}
