package com.scresto.datrain.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.scresto.datrain.data.Itinerary
import com.scresto.datrain.data.PARIS
import java.time.LocalDate

@Composable
internal fun FavoriteCard(
    run: FavoriteRun,
    onOpen: (FavoriteRun) -> Unit,
    showBell: Boolean,
    onBell: () -> Unit,
    onRemove: () -> Unit,
) {
    val rides = run.rides
    val favorite = run.favorite
    Card(Modifier.fillMaxWidth().clickable(enabled = rides != null) { onOpen(run) }) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val day = rides?.let { " · ${favoriteDay(it.first().trip.departure.toLocalDate())}" }.orEmpty()
                // The delay is on the status line below, with its station.
                Text(favorite.legs.joinToString(" + ") { "${it.product} ${it.number}" } + day, fontWeight = FontWeight.SemiBold)
                if (rides == null) {
                    Text("${favorite.fromName} → ${favorite.lastToName}")
                    Text("Ne circule plus dans les horaires connus", color = MaterialTheme.colorScheme.error)
                } else {
                    Text(stationsLine(Itinerary(rides)))
                    statusLine(run)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (showBell) {
                IconButton(onClick = onBell) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = if (favorite.notify) "Couper les notifications de ce favori" else "Activer les notifications de ce favori",
                        tint = if (favorite.notify) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
            FavoriteStar(selected = true, onClick = onRemove)
        }
    }
}

/** "Carbonne 07:14 · voie 2 → Saint-Agne 07:45", with a changed time struck through and followed by the estimate. */
@Composable
private fun stationsLine(itinerary: Itinerary): AnnotatedString {
    val first = itinerary.rides.first()
    val last = itinerary.rides.last()
    val struck = SpanStyle(textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val late = SpanStyle(color = delayColor(), fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        if (itinerary.cancelled) {
            withStyle(struck) {
                append("${first.fromName} ${first.trip.departure.format(TIME_FORMAT)} → ${last.toName} ${last.trip.arrival.format(TIME_FORMAT)}")
            }
            return@buildAnnotatedString
        }
        fun time(planned: java.time.LocalDateTime, delay: Int) {
            if (delay == 0) {
                append(planned.format(TIME_FORMAT))
            } else {
                withStyle(struck) { append(planned.format(TIME_FORMAT)) }
                append(" ")
                withStyle(late) { append(planned.plusMinutes(delay.toLong()).format(TIME_FORMAT)) }
            }
        }
        append("${first.fromName} ")
        time(first.trip.departure, first.trip.departureDelayMinutes)
        first.trip.platform?.let { append(" · voie $it") }
        append(" → ${last.toName} ")
        time(last.trip.arrival, last.trip.arrivalDelayMinutes)
    }
}

/**
 * The most serious state first, then the delay as on the results, then the messages. Train by train details
 * are on the map.
 */
@Composable
private fun statusLine(run: FavoriteRun): AnnotatedString? {
    val itinerary = run.itinerary ?: return null
    val journey = itinerary.rides.size > 1
    val red = SpanStyle(color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
    val cancelled = itinerary.rides.firstOrNull { it.trip.cancelled }
    val risky = itinerary.connections().firstOrNull { it.isAtRisk(TIGHT_CONNECTION_MINUTES) }
    val note = itinerary.rides.firstNotNullOfOrNull { ride ->
        ride.trip.serviceNotes.firstOrNull()?.let { if (journey) "${ride.trip.product} ${ride.trip.number} : $it" else it }
    }
    val delayed = itinerary.rides.any { it.trip.departureDelayMinutes != 0 || it.trip.arrivalDelayMinutes != 0 }
    val delay = delayText(itinerary, java.time.LocalDateTime.now(PARIS))
        ?.let { AnnotatedString(it, SpanStyle(color = delayColor(), fontWeight = FontWeight.SemiBold)) }
    val problem = when {
        risky != null -> AnnotatedString("⚠ Correspondance menacée à ${risky.station}", red)
        note != null -> AnnotatedString("⚠ $note", red)
        else -> null
    }
    val state: AnnotatedString? = when {
        cancelled != null -> AnnotatedString(
            if (journey) "${cancelled.trip.product} ${cancelled.trip.number} supprimé" else "Supprimé",
            red,
        )
        problem != null && delay != null -> problem + AnnotatedString(" · ") + delay
        problem != null || delay != null -> problem ?: delay
        !delayed && run.isLive -> AnnotatedString("À l'heure")
        else -> null
    }
    if (run.messages == 0) return state
    val messages = AnnotatedString(messageCount(run.messages), SpanStyle(color = messageColor(), fontWeight = FontWeight.Medium))
    return if (state == null) messages else state + AnnotatedString(" · ") + messages
}

private fun favoriteDay(date: LocalDate): String {
    val today = LocalDate.now(PARIS)
    return when (date) {
        today -> "aujourd'hui"
        today.plusDays(1) -> "demain"
        else -> formatDay(date)
    }
}
