package com.scresto.datrain.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Favorites followed for one run only, such as a train taken in place of a cancelled one. */
object TemporaryFollows {
    val KEPT_AFTER_ARRIVAL: Duration = Duration.ofMinutes(30)

    /**
     * Over 30 minutes after the run's real arrival, as the background check last knew it; after its planned
     * arrival when it was cancelled or never checked. A run the timetables no longer have stays for its day.
     */
    fun isOver(favorite: FavoriteTrain, planner: JourneyPlanner, states: Map<String, WatchState>, now: LocalDateTime): Boolean {
        val run = favorite.run ?: return false
        val rides = planner.nextJourney(favorite, now) ?: return now.toLocalDate() > LocalDate.parse(run)
        val last = rides.last().trip
        val state = states[FavoriteWatch.legKeys(favorite).last()]?.takeIf { it.departure == last.departure.toString() }
        val arrives = state?.takeUnless { it.cancelled }?.arrivesAt ?: last.arrival
        return now >= arrives.plus(KEPT_AFTER_ARRIVAL)
    }

    /** The temporary follow taken in place of [favorite]'s run of [date]: between the same stations, that day. */
    fun replacement(favorite: FavoriteTrain, date: LocalDate, favorites: List<FavoriteTrain>): FavoriteTrain? =
        favorites.firstOrNull {
            it.run == date.toString() && !it.isSameAs(favorite) &&
                it.fromName == favorite.fromName && it.lastToName == favorite.lastToName
        }
}
