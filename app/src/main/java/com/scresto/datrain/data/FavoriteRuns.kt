package com.scresto.datrain.data

import java.time.Duration
import java.time.LocalDateTime

/**
 * Which run of a favorite to follow: the next one in the timetables, or the one before while it is still
 * going, late, after its planned arrival. One answer for the favorites list, the background check, the widget
 * and the notifications. No network here: the caller passes the live feed when it has one.
 */
object FavoriteRuns {
    /** How long after its planned arrival a run may still be going. */
    val LATE_WINDOW: Duration = Duration.ofHours(6)

    /** [late]: a run whose planned arrival has passed within [LATE_WINDOW], other than [next]. */
    data class Candidates(val next: List<RideLeg>?, val late: List<RideLeg>?)

    fun candidates(favorite: FavoriteTrain, planner: JourneyPlanner, now: LocalDateTime): Candidates {
        val next = planner.nextJourney(favorite, now)
        val late = planner.nextJourney(favorite, now.minus(LATE_WINDOW))
            ?.takeIf { it.last().trip.arrival <= now && ids(it) != ids(next) }
        return Candidates(next, late)
    }

    /**
     * The late run while it is known to be still going: from the live feed when it has the train, else from
     * the saved state of that very run. Otherwise the next run.
     */
    fun choose(candidates: Candidates, feed: LiveFeed?, saved: WatchState?, now: LocalDateTime): List<RideLeg>? {
        val late = candidates.late ?: return candidates.next
        val going = feed?.let { stillGoing(late, it, now) } ?: savedGoing(late, saved, now)
        return if (going) late else candidates.next
    }

    /** Whether the feed shows the run not arrived yet; null when the feed does not have the train. */
    fun stillGoing(rides: List<RideLeg>, feed: LiveFeed, now: LocalDateTime): Boolean? {
        val last = rides.last().trip
        val train = feed.find(last) ?: return null
        if (train.cancelled) return false
        val stop = train.stops.lastOrNull { it.uic == last.toKey && !it.removed } ?: return false
        val arrival = stop.arrivalSeconds ?: return false
        return train.serviceDate.atStartOfDay().plusSeconds((arrival + stop.arrivalDelaySeconds).toLong()) > now
    }

    /** Whether [saved] is about this run, and says it is late and not there yet. */
    fun knows(rides: List<RideLeg>, saved: WatchState?): Boolean =
        saved != null && saved.departure == rides.last().trip.departure.toString()

    private fun savedGoing(rides: List<RideLeg>, saved: WatchState?, now: LocalDateTime): Boolean =
        knows(rides, saved) && !saved!!.cancelled && saved.arrivalTime.plusMinutes(saved.delayMinutes.toLong()) > now

    fun ids(rides: List<RideLeg>?): List<String>? = rides?.map { "${it.trip.number}@${it.trip.departure}" }
}
