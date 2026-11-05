package com.scresto.datrain.widget

import android.content.Context
import com.scresto.datrain.data.FavoriteTrain
import com.scresto.datrain.data.FavoriteWatch
import com.scresto.datrain.data.FavoriteWatchStore
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.RecentHistoryStore
import com.scresto.datrain.data.RideLeg
import com.scresto.datrain.data.TimetableRepository
import com.scresto.datrain.data.WatchState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class WidgetStatus { PLANNED, ON_TIME, DELAYED, CANCELLED, CONNECTION_AT_RISK }

sealed interface WidgetContent {
    data object NoFavorite : WidgetContent
    data object NoTimetable : WidgetContent

    data class Next(
        val favoriteKey: String,
        val title: String,
        /** "Carbonne 07:14 → Saint-Agne 07:45" */
        val planned: String,
        /** The same with estimated times; null without delay. */
        val estimated: String?,
        val delayMinutes: Int,
        val status: WidgetStatus,
    ) : WidgetContent
}

/**
 * The favorite leaving soonest, with what the background check last learned about it.
 * No network here: the widget only shows what is already known.
 */
object WidgetData {
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
    /** Before this, the check has not looked at real time yet: "on time" would mean nothing. */
    private const val LIVE_HOURS = 3L
    private const val TIGHT_CONNECTION_MINUTES = 5L

    suspend fun load(context: Context): WidgetContent {
        val favorites = RecentHistoryStore(context).load().favorites
        if (favorites.isEmpty()) return WidgetContent.NoFavorite
        val planner = TimetableRepository.get(context).plannerOrNull() ?: return WidgetContent.NoTimetable
        val states = FavoriteWatchStore(context).load()
        val now = LocalDateTime.now(PARIS)
        val (favorite, rides) = favorites
            .mapNotNull { favorite -> planner.nextJourney(favorite, FavoriteWatch.watchedFrom(favorite, states, now))?.let { favorite to it } }
            .minByOrNull { (_, rides) -> rides.first().trip.departure }
            ?: return WidgetContent.NoTimetable
        // Only what was learned about this very run counts.
        val legStates = FavoriteWatch.legKeys(favorite).mapIndexed { i, key ->
            states[key]?.takeIf { it.departure == rides.getOrNull(i)?.trip?.departure?.toString() }
        }
        return next(favorite, rides, legStates, now)
    }

    fun next(favorite: FavoriteTrain, rides: List<RideLeg>, legStates: List<WatchState?>, now: LocalDateTime): WidgetContent.Next {
        val first = rides.first()
        val last = rides.last()
        val departureDelay = legStates.firstOrNull()?.delayMinutes ?: 0
        val arrivalDelay = legStates.lastOrNull()?.delayMinutes ?: 0
        val cancelled = legStates.any { it?.cancelled == true }
        val atRisk = legStates.zipWithNext().any { (before, after) ->
            before != null && after != null &&
                FavoriteWatch.connectionAtRisk(before, after.copy(connectionWarned = false), TIGHT_CONNECTION_MINUTES) != null
        }
        val live = legStates.any { it != null && (it.fullChecked || now >= it.departureTime.minusHours(LIVE_HOURS)) }
        val delayed = !cancelled && (departureDelay != 0 || arrivalDelay != 0)
        fun line(departure: LocalDateTime, arrival: LocalDateTime) =
            "${first.fromName} ${departure.format(CLOCK)} → ${last.toName} ${arrival.format(CLOCK)}"
        return WidgetContent.Next(
            favoriteKey = favorite.key,
            title = favorite.legs.joinToString(" + ") { "${it.product} ${it.number}" } + " · " + day(first.trip.departure.toLocalDate(), now.toLocalDate()),
            planned = line(first.trip.departure, last.trip.arrival),
            estimated = if (delayed) {
                line(first.trip.departure.plusMinutes(departureDelay.toLong()), last.trip.arrival.plusMinutes(arrivalDelay.toLong()))
            } else {
                null
            },
            delayMinutes = if (arrivalDelay != 0) arrivalDelay else departureDelay,
            status = when {
                cancelled -> WidgetStatus.CANCELLED
                atRisk -> WidgetStatus.CONNECTION_AT_RISK
                delayed -> WidgetStatus.DELAYED
                live -> WidgetStatus.ON_TIME
                else -> WidgetStatus.PLANNED
            },
        )
    }

    private fun day(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "aujourd'hui"
        today.plusDays(1) -> "demain"
        else -> date.format(DAY)
    }
}
