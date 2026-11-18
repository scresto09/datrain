package com.scresto.datrain.widget

import android.content.Context
import com.scresto.datrain.data.Board
import com.scresto.datrain.data.FavoriteCheck.TIGHT_CONNECTION_MINUTES
import com.scresto.datrain.data.FavoriteRuns
import com.scresto.datrain.data.TemporaryFollows
import com.scresto.datrain.data.Alternatives
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
        /** "TER 872704 · aujourd'hui" */
        val title: String,
        /** Planned departure, shown large. */
        val departure: String,
        /** Estimated departure when it differs; the planned one is then struck through. */
        val newDeparture: String?,
        /** "Carbonne → Saint-Agne 07:45", with the estimated arrival. */
        val route: String,
        val delayMinutes: Int,
        val status: WidgetStatus,
        /** At the boarding station, once announced. */
        val platform: String? = null,
        /** "06:58": when the background check last looked; null if it never did. */
        val checkedAt: String? = null,
        /** SNCF messages about the run, as counted on the favorites list. */
        val messages: Int = 0,
        /** For a cancelled run: "Autres trains : 17:09 · 18:40", the trains before and after it. */
        val alternatives: String? = null,
    ) : WidgetContent
}

/**
 * The favorite leaving soonest, with what the background check last learned about it.
 * No network here: the widget only shows what is already known.
 */
object WidgetData {
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
    /** In place of the last check's time while a refresh is on its way: the widget then hides it. */
    const val REFRESHING = "--:--"

    suspend fun load(context: Context): WidgetContent {
        val all = RecentHistoryStore(context).load().favorites
        if (all.isEmpty()) return WidgetContent.NoFavorite
        val planner = TimetableRepository.get(context).plannerOrNull() ?: return WidgetContent.NoTimetable
        val states = FavoriteWatchStore(context).load()
        val now = LocalDateTime.now(PARIS)
        val favorites = all.filterNot { TemporaryFollows.isOver(it, planner, states, now) }
        val (favorite, rides) = favorites
            .mapNotNull { favorite ->
                val saved = states[FavoriteWatch.legKeys(favorite).last()]
                FavoriteRuns.choose(FavoriteRuns.candidates(favorite, planner, now), null, saved, now)?.let { favorite to it }
            }
            // A cancelled run whose replacement is followed: that one is shown instead.
            .filterNot { (favorite, rides) ->
                val cancelled = cancelled(rides, runStates(favorite, rides, states))
                cancelled && TemporaryFollows.replacement(favorite, rides.first().trip.serviceDate, favorites) != null
            }
            .minByOrNull { (_, rides) -> rides.first().trip.departure }
            ?: return WidgetContent.NoTimetable
        val legStates = runStates(favorite, rides, states)
        val store = FavoriteWatchStore(context)
        val alternatives = if (cancelled(rides, legStates)) {
            Alternatives.find(planner, favorite, rides, now).takeUnless { it.isEmpty }?.let { "Autres trains : ${it.times()}" }
        } else {
            null
        }
        if (store.isRefreshing()) return next(favorite, rides, legStates, now).copy(checkedAt = REFRESHING, alternatives = alternatives)
        val checked = store.checkedAt().takeIf { it > 0 }?.let {
            java.time.Instant.ofEpochMilli(it).atZone(PARIS).toLocalDateTime()
        }
        return next(favorite, rides, legStates, now).copy(
            checkedAt = checked?.takeIf { it.toLocalDate() == now.toLocalDate() }?.format(CLOCK),
            alternatives = alternatives,
        )
    }

    /** Only what was learned about this very run counts. */
    private fun runStates(favorite: FavoriteTrain, rides: List<RideLeg>, states: Map<String, WatchState>): List<WatchState?> =
        FavoriteWatch.legKeys(favorite).mapIndexed { i, key ->
            states[key]?.takeIf { it.departure == rides.getOrNull(i)?.trip?.departure?.toString() }
        }

    /** What the check last saw, otherwise the timetables: a train cancelled in advance may run after all. */
    private fun cancelled(rides: List<RideLeg>, legStates: List<WatchState?>): Boolean =
        rides.indices.any { i -> legStates.getOrNull(i)?.cancelled ?: rides[i].trip.cancelled }

    fun next(favorite: FavoriteTrain, rides: List<RideLeg>, legStates: List<WatchState?>, now: LocalDateTime): WidgetContent.Next {
        val first = rides.first()
        val last = rides.last()
        val departureDelay = legStates.firstOrNull()?.departureDelay ?: 0
        val arrivalDelay = legStates.lastOrNull()?.arrivalDelay ?: 0
        val cancelled = cancelled(rides, legStates)
        val atRisk = legStates.zipWithNext().any { (before, after) ->
            before != null && after != null &&
                FavoriteWatch.connectionAtRisk(before, after.copy(connectionWarned = false), TIGHT_CONNECTION_MINUTES) != null
        }
        // Before a board can show it, the check has not looked at real time yet: "on time" would mean nothing.
        val live = legStates.any { it != null && (it.fullChecked || now >= it.departureTime.minus(Board.HORIZON)) }
        val delayed = !cancelled && (departureDelay != 0 || arrivalDelay != 0)
        val arrival = last.trip.arrival.plusMinutes(if (delayed) arrivalDelay.toLong() else 0)
        return WidgetContent.Next(
            favoriteKey = favorite.key,
            title = favorite.legs.joinToString(" + ") { "${it.product} ${it.number}" } + " · " + day(first.trip.departure.toLocalDate(), now.toLocalDate()),
            departure = first.trip.departure.format(CLOCK),
            newDeparture = first.trip.departure.plusMinutes(departureDelay.toLong()).format(CLOCK).takeIf { delayed && departureDelay != 0 },
            route = "${first.fromName} → ${last.toName} ${arrival.format(CLOCK)}",
            platform = legStates.firstOrNull()?.platform,
            messages = legStates.flatMap { it?.alerts.orEmpty() }.toSet().size,
            // Same rule as the app: before boarding, the departure delay first; on board, the arrival one.
            delayMinutes = if (now < first.trip.departure.plusMinutes(departureDelay.toLong())) {
                departureDelay.takeIf { it != 0 } ?: arrivalDelay
            } else {
                arrivalDelay
            },
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
