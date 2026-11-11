package com.scresto.datrain.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.scresto.datrain.data.Connection
import com.scresto.datrain.data.FavoriteTrain
import com.scresto.datrain.data.Itinerary
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.RideLeg
import com.scresto.datrain.data.ServiceAlert
import com.scresto.datrain.data.TransferLeg
import java.time.Duration
import java.time.LocalDateTime

@Composable
internal fun ItineraryCard(
    itinerary: Itinerary,
    alerts: List<ServiceAlert>?,
    alertsFailed: Boolean,
    onExpand: () -> Unit,
    onTrack: (RideLeg, Itinerary) -> Unit,
    isFavorite: (FavoriteTrain) -> Boolean,
    onToggleFavorite: (FavoriteTrain) -> Unit,
    fromCity: Boolean = false,
    toCity: Boolean = false,
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
            val now = LocalDateTime.now(PARIS)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    timesLine(itinerary),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (itinerary.cancelled) {
                    Text("Supprimé", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                } else {
                    val days = java.time.temporal.ChronoUnit.DAYS.between(itinerary.departure.toLocalDate(), itinerary.arrival.toLocalDate())
                    val duration = formatDuration(Duration.between(itinerary.departure, itinerary.arrival))
                    Text(if (days > 0) "J+$days · $duration" else duration, maxLines = 1)
                }
            }
            statusLine(itinerary, atRisk, now)?.let { Text(it) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val favorites = itinerary.rides.mapNotNull { FavoriteTrain.of(it.trip) } +
                    listOfNotNull(FavoriteTrain.of(itinerary.rides.map { it.trip }).takeIf { itinerary.rides.size > 1 })
                if (favorites.any(isFavorite)) Text("★", color = FAVORITE_YELLOW)
                val messages = alerts?.size ?: 0
                val messageColor = messageColor()
                Text(
                    buildAnnotatedString {
                        // With a whole city chosen, which of its stations is the first thing to know.
                        if (fromCity) append("Depuis ${itinerary.rides.first().fromName} · ")
                        if (toCity) append("Vers ${itinerary.rides.last().toName} · ")
                        append(
                            when (itinerary.changes) {
                                0 -> "Direct"
                                1 -> "1 changement"
                                else -> "${itinerary.changes} changements"
                            } + " · " + itinerary.rides.joinToString(", ") { if (it.isBus) "car" else it.trip.product },
                        )
                        // The first train's platform, known shortly before departure.
                        itinerary.rides.first().trip.platform?.takeUnless { itinerary.cancelled }?.let { append(" · voie $it") }
                        if (messages > 0) {
                            append(" · ")
                            withStyle(SpanStyle(color = messageColor, fontWeight = FontWeight.Medium)) { append(messageCount(messages)) }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ItineraryDetail(itinerary, onTrack, isFavorite, onToggleFavorite)
                AlertsSection(alerts, alertsFailed)
            }
        }
    }
}

@Composable
private fun ConnectionLine(connection: Connection) {
    val text = buildString {
        append("${formatMinutesShort(connection.estimatedMinutes)} de correspondance")
        if (connection.estimatedMinutes != connection.plannedMinutes) {
            append(" (${formatMinutesShort(connection.plannedMinutes)} prévues)")
        }
        val last = connection.walks.lastOrNull()
        when {
            last == null -> append(" à ${connection.station}")
            last.isEstimate -> append(", dont environ ${formatMinutesShort(connection.walkMinutes)} en métro ou RER jusqu'à ${last.toName} (estimation)")
            else -> append(", dont environ ${formatMinutesShort(connection.walkMinutes)} à pied jusqu'à ${last.toName}")
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (connection.isAtRisk(TIGHT_CONNECTION_MINUTES)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/** "Carbonne 07:19 · dans 12 min" for a stop of a result, delays shown as on the map. */
@Composable
private fun stopLine(name: String, planned: LocalDateTime, delayMinutes: Int, cancelled: Boolean, now: LocalDateTime): AnnotatedString {
    if (cancelled) return AnnotatedString("$name ${planned.format(TIME_FORMAT)}", SpanStyle(textDecoration = TextDecoration.LineThrough))
    val estimated = planned.plusMinutes(delayMinutes.toLong())
    val minutes = Duration.between(now, estimated).toMinutes()
    val soon = if (minutes in 1 until SOON_MINUTES) " · dans ${formatMinutesShort(minutes)}" else ""
    return stopText(name, planned.format(TIME_FORMAT), estimated.format(TIME_FORMAT).takeIf { delayMinutes != 0 }, delayMinutes, soon)
}

/** Further than this, "in 14 h" says less than the time itself. */
private const val SOON_MINUTES = 6 * 60L

@Composable
private fun ItineraryDetail(
    itinerary: Itinerary,
    onTrack: (RideLeg, Itinerary) -> Unit,
    isFavorite: (FavoriteTrain) -> Boolean,
    onToggleFavorite: (FavoriteTrain) -> Unit,
) {
    val now = LocalDateTime.now(PARIS)
    val connections = itinerary.connections()
    var rideIndex = 0
    // With changes, the star is the whole journey's: its trains are followed together.
    val journeyFavorite = FavoriteTrain.of(itinerary.rides.map { it.trip }).takeIf { itinerary.rides.size > 1 }
    journeyFavorite?.let { favorite ->
        val selected = isFavorite(favorite)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected) "Trajet en favori" else "Ajouter ce trajet aux favoris",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            FavoriteStar(selected) { onToggleFavorite(favorite) }
        }
    }
    itinerary.legs.forEach { leg ->
        when (leg) {
            // Told in the change line below.
            is TransferLeg -> Unit
            is RideLeg -> Column {
                connections.getOrNull(rideIndex - 1)?.let { ConnectionLine(it) }
                rideIndex++
                val trip = leg.trip
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${trip.product} ${trip.number}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (itinerary.rides.size == 1) {
                        FavoriteTrain.of(trip)?.let { favorite -> FavoriteStar(isFavorite(favorite)) { onToggleFavorite(favorite) } }
                    }
                }
                Text(stopLine(leg.fromName, trip.departure, trip.departureDelayMinutes, trip.cancelled, now))
                Text(stopLine(leg.toName, trip.arrival, trip.arrivalDelayMinutes, trip.cancelled, now))
                Text("Direction ${trip.destination}", style = MaterialTheme.typography.bodySmall)
                trip.platform?.let { Text("Voie $it", style = MaterialTheme.typography.bodyMedium) }
                // On a direct journey the card already says it; with changes, it tells which train.
                if (itinerary.rides.size > 1) {
                    trip.serviceNotes.forEach { note ->
                        Text("⚠ $note", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    }
                }
                // Delays are on the station lines above; only a cancellation needs saying here.
                if (trip.cancelled && itinerary.rides.size > 1) {
                    Text("Supprimé", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
                if (trip.isTrackableAt(now)) {
                    TextButton(onClick = { onTrack(leg, itinerary) }, contentPadding = PaddingValues(0.dp)) {
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
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().clickable { open = !open },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    alerts.forEach { alert ->
                        Text("⚠ ${alert.title}", fontWeight = FontWeight.SemiBold)
                        if (open && alert.description.isNotEmpty()) {
                            Text(alert.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (alerts.any { it.description.isNotEmpty() }) {
                        Text(if (open) "Masquer le détail" else "Voir le détail", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** The line shown in the results list, so a delay is visible without opening the journey. */
/** "08:05 → 12:31 12:46": the arrival corrected, or the departure when only that one is late. */
@Composable
private fun timesLine(itinerary: Itinerary): AnnotatedString {
    val first = itinerary.rides.first().trip
    val last = itinerary.rides.last().trip
    val struck = SpanStyle(textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)
    val late = SpanStyle(color = delayColor())
    return buildAnnotatedString {
        if (itinerary.cancelled) {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                append("${first.departure.format(TIME_FORMAT)} → ${last.arrival.format(TIME_FORMAT)}")
            }
            return@buildAnnotatedString
        }
        val departureOnly = last.arrivalDelayMinutes == 0 && first.departureDelayMinutes != 0
        if (departureOnly) {
            withStyle(struck) { append(first.departure.format(TIME_FORMAT)) }
            append(" ")
            withStyle(late) { append(first.estimatedDeparture.format(TIME_FORMAT)) }
        } else {
            append(first.departure.format(TIME_FORMAT))
        }
        append(" → ")
        if (last.arrivalDelayMinutes != 0) {
            withStyle(struck) { append(last.arrival.format(TIME_FORMAT)) }
            append(" ")
            withStyle(late) { append(last.estimatedArrival.format(TIME_FORMAT)) }
        } else {
            append(last.arrival.format(TIME_FORMAT))
        }
    }
}

/**
 * One line under the times: the most serious problem in red, then the delay in orange.
 * Before the train leaves the traveller's station, its departure there; after, its arrival at theirs.
 */
@Composable
private fun statusLine(itinerary: Itinerary, atRisk: Boolean, now: LocalDateTime): AnnotatedString? {
    if (itinerary.cancelled) return null
    val delay = delayText(itinerary, now)
    val risky = if (atRisk) itinerary.connections().firstOrNull { it.isAtRisk(TIGHT_CONNECTION_MINUTES) } else null
    val problem = when {
        risky != null -> "⚠ Correspondance menacée à ${risky.station}"
        atRisk -> "⚠ Correspondance menacée"
        else -> itinerary.rides.firstNotNullOfOrNull { it.trip.serviceNotes.firstOrNull() }?.let { "⚠ $it" }
    }
    if (problem == null && delay == null) return null
    val red = SpanStyle(color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
    val orange = SpanStyle(color = delayColor(), fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        problem?.let { withStyle(red) { append(it) } }
        if (problem != null && delay != null) append(" · ")
        delay?.let { withStyle(orange) { append(it) } }
    }
}

/**
 * "Départ de Carbonne à 07:29 (+15 min)" before the train leaves the traveller's station, then "Arrivée à
 * Saint-Agne à 08:00 (+15 min)": each delay said with its station, so that one is not taken for the other.
 * Null when on time. Shared by the results and the favorites.
 */
internal fun delayText(itinerary: Itinerary, now: LocalDateTime): String? {
    val first = itinerary.rides.first()
    val last = itinerary.rides.last()
    return when {
        now < first.trip.estimatedDeparture && first.trip.departureDelayMinutes != 0 ->
            "Départ de ${first.fromName} à ${first.trip.estimatedDeparture.format(TIME_FORMAT)} (${signed(first.trip.departureDelayMinutes)})"
        last.trip.arrivalDelayMinutes != 0 ->
            "Arrivée à ${last.toName} à ${last.trip.estimatedArrival.format(TIME_FORMAT)} (${signed(last.trip.arrivalDelayMinutes)})"
        else -> null
    }
}

private fun signed(minutes: Int) = "${if (minutes > 0) "+" else ""}$minutes min"
