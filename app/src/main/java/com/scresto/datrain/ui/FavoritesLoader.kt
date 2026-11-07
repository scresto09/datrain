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

    /** Throws [NomadeError] when the live feed cannot be read. */
    suspend fun live(runs: List<FavoriteRun>, now: LocalDateTime): List<FavoriteRun> {
        val feed = nomade.liveFeed()
        // As for the results: a train missing from that feed may be in SNCF's own, read only then.
        val soon = runs.flatMap { it.late.orEmpty() + it.next.orEmpty() }.filter { ride ->
            ride.trip.departure < now.plus(REALTIME_HORIZON) && ride.trip.arrival > now.minus(REALTIME_HORIZON)
        }
        val updates = if (soon.any { feed.find(it.trip) == null }) {
            try {
                realtime.tripUpdates()
            } catch (e: NomadeError) {
                emptyList()
            }
        } else {
            emptyList()
        }
        return runs.map { liveRun(it, feed, updates, now) }.sortedForDisplay()
    }

    /** Throws [NomadeError] when the alerts cannot be read. */
    suspend fun messages(runs: List<FavoriteRun>): List<FavoriteRun> {
        val alerts = alertsCache.get()
        return runs.map { run -> run.copy(messages = run.itinerary?.let { alertsFor(it, alerts).size } ?: 0) }
    }

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

    private fun liveRun(run: FavoriteRun, feed: LiveFeed, updates: List<RealtimeTrip>, now: LocalDateTime): FavoriteRun {
        // Planned arrival passed, but the train is late and not there yet: it stays the one shown.
        run.late?.let { late ->
            val updated = RealtimeOverlay.apply(listOf(Itinerary(late)), feed, updates).single().rides
            // The shared rule, with SNCF's own feed as a second source for a train Train Nomade does not have.
            val going = FavoriteRuns.stillGoing(late, feed, now)
                ?: (updated != late && !Itinerary(updated).cancelled && updated.last().trip.estimatedArrival > now)
            if (going) return run.copy(rides = updated, isLive = true)
        }
        if (!run.favorite.isJourney) {
            val cancelled = FavoriteWatch.missingRun(run.favorite, run.next?.firstOrNull(), feed, now)
            if (cancelled != null) return run.copy(rides = listOf(cancelled), isLive = true)
        }
        val rides = run.next ?: return run.copy(rides = null)
        val updated = RealtimeOverlay.apply(listOf(Itinerary(rides)), feed, updates).single().rides
        val live = rides.any { feed.find(it.trip) != null } || updated != rides
        return run.copy(rides = updated, isLive = live)
    }
}

/** Soonest first; those no longer in the timetables last. */
internal fun List<FavoriteRun>.sortedForDisplay() =
    sortedWith(compareBy({ it.ride == null }, { it.ride?.trip?.departure }))
