package com.scresto.datrain.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.scresto.datrain.R
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.Trip
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.FRENCH)
private val THIS_YEAR_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
internal val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
internal val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
internal val SHORT_DAY_FORMAT = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)
/** Below this, a change is flagged as risky. */
internal const val TIGHT_CONNECTION_MINUTES = 5L
internal val FAVORITE_YELLOW = Color(0xFFF9A825)
internal val LOGO_RED = Color(0xFFD32F2F)
private const val LOGO_RATIO = 4.1057f

/** The word takes the theme's text colour; the dot stays red. */
@Composable
internal fun DatrainLogo(onClick: (() -> Unit)? = null) {
    val size = Modifier.height(24.dp).aspectRatio(LOGO_RATIO)
    Box(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
        Image(
            painterResource(R.drawable.logo_word),
            contentDescription = "Datrain",
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
            modifier = size,
        )
        Image(painterResource(R.drawable.logo_dot), contentDescription = null, modifier = size)
    }
}

/** With a delay, the planned time struck through, then the estimated one and the gap in red. */
@Composable
internal fun stopText(name: String, planned: String, estimated: String?, delayMinutes: Int, after: String): AnnotatedString {
    val red = delayColor()
    return buildAnnotatedString {
        append("$name ")
        if (estimated == null) {
            append(planned)
        } else {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(planned) }
            append(" ")
            withStyle(SpanStyle(color = red, fontWeight = FontWeight.SemiBold)) {
                append("$estimated (${if (delayMinutes > 0) "+" else ""}$delayMinutes min)")
            }
        }
        append(after)
    }
}

internal fun formatMinutesShort(minutes: Long): String {
    if (minutes < 60) return "$minutes min"
    val rest = minutes % 60
    return if (rest == 0L) "${minutes / 60} h" else "${minutes / 60} h ${rest.toString().padStart(2, '0')}"
}

internal fun messageCount(count: Int): String = if (count == 1) "1 message" else "$count messages"

/** Orange for a delay; red is kept for what breaks the journey: cancellation, removed stop, missed change. */
@Composable
internal fun delayColor(): Color = if (isSystemInDarkTheme()) Color(0xFFFF8A3D) else Color(0xFFE65100)

/** Amber: worth a look, unlike the red kept for what changes the journey. */
@Composable
internal fun messageColor(): Color = if (isSystemInDarkTheme()) Color(0xFFFFB74D) else Color(0xFFB26A00)

@Composable
internal fun FavoriteStar(selected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painterResource(if (selected) R.drawable.ic_star else R.drawable.ic_star_border),
            contentDescription = if (selected) "Retirer des favoris" else "Ajouter aux favoris",
            tint = if (selected) FAVORITE_YELLOW else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(30.dp),
        )
    }
}

internal fun realtimeLabel(trip: Trip, showCancelled: Boolean): String? {
    if (trip.cancelled) return if (showCancelled) "Supprimé" else null
    val parts = buildList {
        if (trip.departureDelayMinutes > 0) add("Départ +${trip.departureDelayMinutes} min")
        if (trip.arrivalDelayMinutes > 0) add("Arrivée +${trip.arrivalDelayMinutes} min")
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

/**
 * The delay that matters to the traveller: before boarding, at their departure (or else at arrival);
 * once on board, only at their arrival. The same rule on every screen.
 */
internal fun travellerDelay(departureDelay: Int, arrivalDelay: Int, boarded: Boolean): Int? =
    if (boarded) arrivalDelay.takeIf { it != 0 } else (departureDelay.takeIf { it != 0 } ?: arrivalDelay.takeIf { it != 0 })

internal fun formatDay(date: LocalDate): String =
    date.format(if (date.year == LocalDate.now(PARIS).year) THIS_YEAR_DATE_FORMAT else DATE_FORMAT)

internal fun formatDuration(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    return if (hours > 0) "${hours}h${minutes.toString().padStart(2, '0')}" else "$minutes min"
}
