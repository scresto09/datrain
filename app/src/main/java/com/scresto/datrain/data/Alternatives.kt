package com.scresto.datrain.data

import java.time.Duration
import java.time.LocalDateTime

/**
 * In place of a cancelled run, between the same stations that day: the train before it, while it can still be
 * caught, and the one after it. A direct train first; a journey with changes only when it arrives earlier, or
 * when no direct train is left. No network: real time is applied when the caller has it.
 */
data class Alternatives(val before: Itinerary?, val after: Itinerary?) {
    val isEmpty: Boolean get() = before == null && after == null

    /** "17:09 · 18:40", for the widget and the notification. */
    fun times(): String = listOfNotNull(before, after).joinToString(" · ") { it.departure.toLocalTime().toString() }

    companion object {
        /** Trains leaving up to this long before the cancelled one are looked at. */
        val LOOK_BEFORE: Duration = Duration.ofHours(1)

        fun find(
            planner: JourneyPlanner,
            favorite: FavoriteTrain,
            cancelled: List<RideLeg>,
            now: LocalDateTime,
            feed: LiveFeed? = null,
            updates: List<RealtimeTrip> = emptyList(),
        ): Alternatives = find(planner, favorite, cancelled.first().trip.departure, Itinerary(cancelled).id, now, feed, updates)

        /** [leaves]: when the cancelled run was to leave; [cancelledId]: that run, never offered in its own place. */
        fun find(
            planner: JourneyPlanner,
            favorite: FavoriteTrain,
            leaves: LocalDateTime,
            cancelledId: String?,
            now: LocalDateTime,
            feed: LiveFeed? = null,
            updates: List<RealtimeTrip> = emptyList(),
        ): Alternatives {
            val none = Alternatives(null, null)
            val from = planner.resolveAll(Station(favorite.fromName, favorite.fromUic, -1)) ?: return none
            val last = favorite.legs.last()
            val to = planner.resolveAll(Station(last.toName, last.toUic, -1)) ?: return none
            val date = leaves.toLocalDate()
            val start = Duration.between(date.atStartOfDay(), leaves.minus(LOOK_BEFORE)).seconds.toInt().coerceAtLeast(0)
            val found = RealtimeOverlay.apply(planner.search(from, to, date, start), feed, updates)
            val usable = found.filter { !it.cancelled && it.id != cancelledId }
            // Not the same train: any other one, as long as it has not left.
            val before = usable.filter { it.departure < leaves && it.rides.first().trip.estimatedDeparture > now }
            val after = usable.filter { it.departure >= leaves }
            return Alternatives(
                best(before) { it.maxByOrNull { itinerary -> itinerary.departure } },
                best(after) { it.minByOrNull { itinerary -> itinerary.departure } },
            )
        }

        /** The direct train [pick] chooses; a journey with changes only if it arrives earlier, or if none is direct. */
        private fun best(candidates: List<Itinerary>, pick: (List<Itinerary>) -> Itinerary?): Itinerary? {
            val direct = pick(candidates.filter { it.changes == 0 })
            val withChanges = candidates.filter { it.changes > 0 }.minByOrNull { it.rides.last().trip.estimatedArrival }
            return when {
                direct == null -> withChanges
                withChanges != null && withChanges.rides.last().trip.estimatedArrival < direct.rides.last().trip.estimatedArrival -> withChanges
                else -> direct
            }
        }
    }
}
