package com.scresto.datrain.ui

import com.scresto.datrain.data.CancelledQuery
import com.scresto.datrain.data.GtfsRealtimeApi
import com.scresto.datrain.data.Itinerary
import com.scresto.datrain.data.LiveFeed
import com.scresto.datrain.data.NomadeApi
import com.scresto.datrain.data.NomadeError
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.RealtimeOverlay
import com.scresto.datrain.data.RealtimeTrip
import com.scresto.datrain.data.ServiceAlert
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.LocalDateTime

/** Real time is only read for trains within the live feed's reach of now. */
internal val REALTIME_HORIZON: Duration = LiveFeed.HORIZON

/** Search results completed with real time: delays, and trains the timetables could not give. */
internal class ResultsRealtime(private val nomade: NomadeApi, private val realtime: GtfsRealtimeApi) {
    class Applied(val itineraries: List<Itinerary>, val unavailable: Boolean)

    /** Null when no result is near enough in time for real time to say anything. */
    suspend fun apply(itineraries: List<Itinerary>, query: CancelledQuery, now: LocalDateTime): Applied? {
        val soon = itineraries.flatMap { it.rides }.filter { ride ->
            ride.trip.departure < now.plus(REALTIME_HORIZON) && ride.trip.arrival > now.minus(REALTIME_HORIZON)
        }
        // Today, the live feed may also hold cancelled trains that the search could not find.
        if (soon.isEmpty() && query.date != now.toLocalDate()) return null

        var unavailable = false
        val feed: LiveFeed? = try {
            nomade.liveFeed()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NomadeError) {
            unavailable = true
            null
        }
        // SNCF's own feed only for trains Train Nomade does not have.
        val updates: List<RealtimeTrip> = if (soon.none { feed?.find(it.trip) == null }) {
            emptyList()
        } else {
            try {
                realtime.tripUpdates()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NomadeError) {
                if (feed == null) unavailable = true
                emptyList()
            }
        }
        val updated = RealtimeOverlay.apply(itineraries, feed, updates)
        val extra = feed?.let { live ->
            RealtimeOverlay.cancelledMissing(live, query, updated) + RealtimeOverlay.lateDepartures(live, query, updated)
        }.orEmpty()
        val all = (updated + extra).distinctBy { it.id }
        return Applied(
            if (query.arriveBy) all.sortedByDescending { it.arrival } else all.sortedBy { it.departure },
            unavailable,
        )
    }
}

/** The alerts that concern one of the journey's trains on its day. */
internal fun alertsFor(itinerary: Itinerary, alerts: List<ServiceAlert>): List<ServiceAlert> =
    alerts.filter { alert ->
        itinerary.rides.any { ride ->
            val trip = ride.trip
            val concerned = alert.concerns(trip.number.split('-'), trip.fromKey, trip.toKey)
            concerned && alert.isActiveAt(trip.departure.atZone(PARIS).toEpochSecond())
        }
    }.distinctBy { it.title to it.description }
