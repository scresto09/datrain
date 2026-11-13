package com.scresto.datrain.ui

import android.content.Context
import com.scresto.datrain.data.AlertsCache
import com.scresto.datrain.data.FavoriteRuns
import com.scresto.datrain.data.FavoriteTrain
import com.scresto.datrain.data.FavoriteWatch
import com.scresto.datrain.data.FavoriteWatchStore
import com.scresto.datrain.data.GtfsRealtimeApi
import com.scresto.datrain.data.Itinerary
import com.scresto.datrain.data.JourneyPlanner
import com.scresto.datrain.data.LiveFeed
import com.scresto.datrain.data.NomadeApi
import com.scresto.datrain.data.NomadeError
import com.scresto.datrain.data.RealtimeOverlay
import com.scresto.datrain.data.RealtimeTrip
import com.scresto.datrain.data.RideLeg
import com.scresto.datrain.data.ServiceAlert
import com.scresto.datrain.data.WatchState
import java.time.LocalDateTime

/**
 * The favorites list in three steps, each shown as soon as it is known: runs from the timetables and the
 * background check's saved states, then real time, then the number of messages.
 */
internal class FavoritesLoader(
    private val context: Context,
    private val nomade: NomadeApi,
    private val realtime: GtfsRealtimeApi,
    private val alertsCache: AlertsCache,
) {
    /** No network. A run already on screen keeps its delays and messages, so that nothing jumps. */
    fun planned(favorites: List<FavoriteTrain>, planner: JourneyPlanner, shown: List<FavoriteRun>, now: LocalDateTime): List<FavoriteRun> {
        val saved = FavoriteWatchStore(context).load()
        return favorites.map { favorite ->
            val candidates = FavoriteRuns.candidates(favorite, planner, now)
            val chosen = FavoriteRuns.choose(candidates, null, saved[FavoriteWatch.legKeys(favorite).last()], now)
            val planned = FavoriteRun(favorite, chosen, next = candidates.next, late = candidates.late)
            shown.firstOrNull { it.favorite.key == favorite.key && FavoriteRuns.ids(it.rides) == FavoriteRuns.ids(chosen) }
                ?.copy(favorite = favorite, next = candidates.next, late = candidates.late)
                ?: planned
        }.sortedForDisplay()
    }

    /** Throws [NomadeError] when no real time can be read. */
    suspend fun live(runs: List<FavoriteRun>, now: LocalDateTime): List<FavoriteRun> {
        // An out-of-date feed counts as none: its trains would show as on time.
        val feed = nomade.liveFeed().takeIf { it.isFresh() }
        // As for the results: a train missing from that feed may be in SNCF's own, read only then.
        val soon = runs.flatMap { it.late.orEmpty() + it.next.orEmpty() }.filter { ride ->
            ride.trip.departure < now.plus(REALTIME_HORIZON) && ride.trip.arrival > now.minus(REALTIME_HORIZON)
        }
        val updates = if (soon.any { feed?.find(it.trip) == null }) {
            try {
                realtime.tripUpdates()
            } catch (e: NomadeError) {
                if (feed == null) throw e
                emptyList()
            }
        } else {
            emptyList()
        }
        return runs.map { liveRun(it, feed, updates, now) }.sortedForDisplay()
    }

    /**
     * Whether these runs, read by the screen, say something the background check has not saved: another delay,
     * a cancellation, or another number of messages when [messagesKnown]. Only for [watched] favorites, the
     * ones that check follows.
     */
    fun aheadOfSaved(runs: List<FavoriteRun>, watched: (FavoriteTrain) -> Boolean, messagesKnown: Boolean): Boolean {
        val saved = FavoriteWatchStore(context).load()
        return runs.any { run ->
            val rides = run.rides
            if (rides == null || !run.isLive || !watched(run.favorite)) return@any false
            val states = FavoriteWatch.legKeys(run.favorite).mapIndexed { i, key ->
                saved[key]?.takeIf { it.departure == rides.getOrNull(i)?.trip?.departure?.toString() }
            }
            val messages = messagesKnown && run.messages != states.flatMap { it?.alerts.orEmpty() }.toSet().size
            messages || rides.zip(states).any { (ride, state) ->
                state == null || state.cancelled != ride.trip.cancelled ||
                    state.departureDelay != ride.trip.departureDelayMinutes || state.arrivalDelay != ride.trip.arrivalDelayMinutes
            }
        }
    }

    /** What [aheadOfSaved] compares, as one string: the same string means nothing new was seen. */
    fun liveSignature(runs: List<FavoriteRun>, watched: (FavoriteTrain) -> Boolean, messagesKnown: Boolean): String =
        runs.filter { it.isLive && watched(it.favorite) }.joinToString("|") { run ->
            run.favorite.key + (if (messagesKnown) "#${run.messages}" else "") + run.rides.orEmpty().joinToString("") { ride ->
                with(ride.trip) { ";$departure,$departureDelayMinutes,$arrivalDelayMinutes,$cancelled" }
            }
        }

    /** Throws [NomadeError] when the alerts cannot be read. */
    suspend fun alerts(): List<ServiceAlert> = alertsCache.get()

    /** The number of messages of each run, for the run it shows now. */
    fun withMessages(runs: List<FavoriteRun>, alerts: List<ServiceAlert>): List<FavoriteRun> =
        runs.map { run -> run.copy(messages = run.itinerary?.let { alertsFor(it, alerts).size } ?: 0) }

    /** The run a notification is about: a late one still going, otherwise the next. */
    suspend fun runToOpen(favorite: FavoriteTrain, planner: JourneyPlanner, now: LocalDateTime): List<RideLeg>? {
        val candidates = FavoriteRuns.candidates(favorite, planner, now)
        val feed = if (candidates.late != null) {
            try {
                nomade.liveFeed()
            } catch (e: NomadeError) {
                null
            }
        } else {
            null
        }
        return FavoriteRuns.choose(candidates, feed, savedState(favorite), now)
    }

    private fun savedState(favorite: FavoriteTrain): WatchState? =
        FavoriteWatchStore(context).load()[FavoriteWatch.legKeys(favorite).last()]

    private fun liveRun(run: FavoriteRun, feed: LiveFeed?, updates: List<RealtimeTrip>, now: LocalDateTime): FavoriteRun {
        // Planned arrival passed, but the train is late and not there yet: it stays the one shown.
        run.late?.let { late ->
            val updated = RealtimeOverlay.apply(listOf(Itinerary(late)), feed, updates).single().rides
            // The shared rule, with SNCF's own feed as a second source for a train Train Nomade does not have.
            val going = feed?.let { FavoriteRuns.stillGoing(late, it, now) }
                ?: (updated != late && !Itinerary(updated).cancelled && updated.last().trip.estimatedArrival > now)
            if (going) return run.copy(rides = updated, isLive = true)
        }
        if (feed != null && !run.favorite.isJourney) {
            val cancelled = FavoriteWatch.missingRun(run.favorite, run.next?.firstOrNull(), feed, now)
            if (cancelled != null) return run.copy(rides = listOf(cancelled), isLive = true)
        }
        val rides = run.next ?: return run.copy(rides = null)
        val updated = RealtimeOverlay.apply(listOf(Itinerary(rides)), feed, updates).single().rides
        val live = rides.any { feed?.find(it.trip) != null } || updated != rides
        return run.copy(rides = updated, isLive = live)
    }
}

/** Soonest first; those no longer in the timetables last. */
internal fun List<FavoriteRun>.sortedForDisplay() =
    sortedWith(compareBy({ it.ride == null }, { it.ride?.trip?.departure }))
