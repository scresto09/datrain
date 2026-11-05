package com.scresto.datrain.data

import java.time.LocalDate
import java.time.LocalDateTime

/** A timetable station. [index] can change with each update: it is found again by name. */
data class Station(val name: String, val uic: Int, val index: Int)

/** One train of a journey, between the stations where the traveller boards and alights. */
data class Trip(
    val number: String,
    val product: String,
    val destination: String,
    val serviceDate: LocalDate,
    val departure: LocalDateTime,
    val arrival: LocalDateTime,
    val departureDelayMinutes: Int = 0,
    val arrivalDelayMinutes: Int = 0,
    val platform: String? = null,
    val cancelled: Boolean = false,
    /** Changes to the train's route, such as an exceptional terminus. */
    val serviceNotes: List<String> = emptyList(),
    val fromKey: String = "",
    val toKey: String = "",
    /** The planned route: it places on the map a train missing from live tracking. */
    val plannedStops: List<LiveStop> = emptyList(),
    val plannedStations: Map<String, StationInfo> = emptyMap(),
) {
    /** Today, or still running (a night train that left yesterday can be followed after midnight). */
    fun isTrackableAt(now: LocalDateTime): Boolean =
        departure.toLocalDate() == now.toLocalDate() || (departure <= now && now <= arrival)

    val estimatedDeparture: LocalDateTime get() = departure.plusMinutes(departureDelayMinutes.toLong())
    val estimatedArrival: LocalDateTime get() = arrival.plusMinutes(arrivalDelayMinutes.toLong())
}

sealed interface ItineraryLeg

data class RideLeg(val trip: Trip, val fromName: String, val toName: String, val isBus: Boolean) : ItineraryLeg

/** Change between two stations. [isEstimate]: duration the timetables do not give (large Paris stations). */
data class TransferLeg(val fromName: String, val toName: String, val seconds: Int, val isEstimate: Boolean) : ItineraryLeg

data class Itinerary(val legs: List<ItineraryLeg>) {
    val rides: List<RideLeg> = legs.filterIsInstance<RideLeg>()
    val id: String = rides.joinToString("|") { "${it.trip.number}@${it.trip.departure}" }
    val departure: LocalDateTime get() = rides.first().trip.departure
    val arrival: LocalDateTime get() = rides.last().trip.arrival
    val changes: Int get() = rides.size - 1
    val cancelled: Boolean get() = rides.any { it.trip.cancelled }

    /** Minutes left at each change, delays included. */
    fun connectionMargins(): List<Long> = margins { it.estimatedArrival to it.estimatedDeparture }

    /**
     * A delay has shrunk a change enough to make it risky. Without a delay, the planned margin
     * can be short: the search has already checked the change time.
     */
    fun hasConnectionAtRisk(tightMinutes: Long): Boolean {
        val planned = margins { it.arrival to it.departure }
        return connectionMargins().indices.any { i ->
            val estimated = connectionMargins()[i]
            estimated < planned[i] && estimated < tightMinutes
        }
    }

    /** Each change, from one train's arrival to the next one's departure. */
    fun connections(): List<Connection> = buildList {
        val walks = ArrayList<TransferLeg>()
        var previous: RideLeg? = null
        for (leg in legs) {
            when (leg) {
                is TransferLeg -> walks += leg
                is RideLeg -> {
                    previous?.let { before ->
                        add(
                            Connection(
                                station = before.toName,
                                plannedMinutes = java.time.Duration.between(before.trip.arrival, leg.trip.departure).toMinutes(),
                                estimatedMinutes = java.time.Duration.between(before.trip.estimatedArrival, leg.trip.estimatedDeparture).toMinutes(),
                                walks = walks.toList(),
                            ),
                        )
                    }
                    previous = leg
                    walks.clear()
                }
            }
        }
    }

    private fun margins(times: (Trip) -> Pair<LocalDateTime, LocalDateTime>): List<Long> = buildList {
        var transfer = 0L
        var previous: RideLeg? = null
        for (leg in legs) {
            when (leg) {
                is TransferLeg -> transfer += leg.seconds
                is RideLeg -> {
                    previous?.let { before ->
                        val gap = java.time.Duration.between(times(before.trip).first, times(leg.trip).second).seconds
                        add((gap - transfer) / 60)
                    }
                    previous = leg
                    transfer = 0
                }
            }
        }
    }
}

sealed class NomadeError : Exception() {
    data object RateLimited : NomadeError() { private fun readResolve(): Any = RateLimited }
    data object Network : NomadeError() { private fun readResolve(): Any = Network }
    data class Api(val detail: String) : NomadeError()
}

/** A change between two trains. [walks]: going on foot, or across Paris, to another station first. */
data class Connection(
    val station: String,
    val plannedMinutes: Long,
    val estimatedMinutes: Long,
    val walks: List<TransferLeg>,
) {
    val walkMinutes: Long get() = walks.sumOf { it.seconds } / 60L

    /** Same rule as [Itinerary.hasConnectionAtRisk]. */
    fun isAtRisk(tightMinutes: Long): Boolean =
        estimatedMinutes < plannedMinutes && estimatedMinutes - walkMinutes < tightMinutes
}
