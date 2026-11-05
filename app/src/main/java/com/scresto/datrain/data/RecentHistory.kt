package com.scresto.datrain.data

import android.content.Context
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime

private const val MAX_STATIONS = 5
private const val MAX_JOURNEYS = 10
private const val MAX_TRAINS = 5
private const val MAX_NUMBERS = 10
/** After this, following a train on the map is pointless. */
private const val TRAIN_KEPT_HOURS = 3L

@Serializable
data class RecentStation(val name: String, val uic: Int) {
    fun toStation() = Station(name, uic, index = -1)
}

@Serializable
data class RecentJourney(val from: RecentStation, val to: RecentStation)

/**
 * A train the user follows, between their own stations. The time is not kept: it shifts by
 * a few minutes between timetables and is read again from the current ones.
 */
@Serializable
data class FavoriteTrain(
    val number: String,
    val product: String,
    val fromName: String,
    val toName: String,
    val fromUic: Int = 0,
    val toUic: Int = 0,
    val notify: Boolean = true,
    /** The trains taken after this one, for a journey with changes. */
    val connections: List<FavoriteTrain> = emptyList(),
) {
    /** One train alone keeps the key it had before journeys existed. */
    val key: String get() = legs.joinToString(">") { "${it.number}|${it.fromName}|${it.toName}" }

    /** Every train of the favorite, this one first. */
    val legs: List<FavoriteTrain> get() = listOf(copy(connections = emptyList())) + connections

    val isJourney: Boolean get() = connections.isNotEmpty()

    val lastToName: String get() = connections.lastOrNull()?.toName ?: toName

    fun isSameAs(other: FavoriteTrain) = key == other.key

    companion object {
        /** A journey with changes, or null if one of its trains could not be found again. */
        fun of(trips: List<Trip>): FavoriteTrain? {
            val legs = trips.map { of(it) ?: return null }
            return legs.firstOrNull()?.copy(connections = legs.drop(1))
        }

        /** Null for a train whose stops are unknown: it could not be found again. */
        fun of(trip: Trip): FavoriteTrain? {
            val fromKey = trip.fromKey.ifEmpty { trip.plannedStops.firstOrNull()?.uic } ?: return null
            val toKey = trip.toKey.ifEmpty { trip.plannedStops.lastOrNull()?.uic } ?: return null
            val fromName = trip.plannedStations[fromKey]?.name ?: return null
            val toName = trip.plannedStations[toKey]?.name ?: return null
            return FavoriteTrain(
                trip.number, trip.product, fromName, toName,
                fromKey.toIntOrNull() ?: 0, toKey.toIntOrNull() ?: 0,
            )
        }
    }
}

/** A train whose map was opened: enough to open it again without a new search. */
@Serializable
data class RecentTrain(
    val number: String,
    val product: String,
    val destination: String,
    val fromName: String,
    val serviceDate: String,
    val departure: String,
    val arrival: String,
    val fromKey: String,
    val toKey: String,
    val stops: List<LiveStop> = emptyList(),
    val stations: Map<String, StationInfo> = emptyMap(),
) {
    fun toTrip() = Trip(
        number = number,
        product = product,
        destination = destination,
        serviceDate = LocalDate.parse(serviceDate),
        departure = LocalDateTime.parse(departure),
        arrival = LocalDateTime.parse(arrival),
        fromKey = fromKey,
        toKey = toKey,
        plannedStops = stops,
        plannedStations = stations,
    )

    fun isOver(now: LocalDateTime): Boolean = LocalDateTime.parse(arrival).plusHours(TRAIN_KEPT_HOURS) < now

    companion object {
        fun of(ride: RideLeg) = RecentTrain(
            number = ride.trip.number,
            product = ride.trip.product,
            destination = ride.trip.destination,
            fromName = ride.fromName,
            serviceDate = ride.trip.serviceDate.toString(),
            departure = ride.trip.departure.toString(),
            arrival = ride.trip.arrival.toString(),
            fromKey = ride.trip.fromKey,
            toKey = ride.trip.toKey,
            stops = ride.trip.plannedStops,
            stations = ride.trip.plannedStations,
        )
    }
}

/** Most recent first. */
@Serializable
data class RecentHistory(
    val stations: List<RecentStation> = emptyList(),
    val journeys: List<RecentJourney> = emptyList(),
    val trains: List<RecentTrain> = emptyList(),
    val numbers: List<String> = emptyList(),
    val favorites: List<FavoriteTrain> = emptyList(),
    val notifications: Boolean = true,
) {
    fun adding(from: Station, to: Station): RecentHistory {
        val start = RecentStation(from.name, from.uic)
        val end = RecentStation(to.name, to.uic)
        return copy(
            stations = (listOf(start, end) + stations).distinctBy { it.name }.take(MAX_STATIONS),
            journeys = (listOf(RecentJourney(start, end)) + journeys).distinct().take(MAX_JOURNEYS),
        )
    }

    fun addingTrain(train: RecentTrain) = copy(
        trains = (listOf(train) + trains).distinctBy { it.number to it.departure }.take(MAX_TRAINS),
    )

    fun isFavorite(train: FavoriteTrain) = favorites.any { it.isSameAs(train) }

    fun addingFavorite(train: FavoriteTrain) = if (isFavorite(train)) this else copy(favorites = favorites + train)

    fun withoutFavorite(train: FavoriteTrain) = copy(favorites = favorites.filterNot { it.isSameAs(train) })

    fun withFavoriteNotify(train: FavoriteTrain, notify: Boolean) = copy(
        favorites = favorites.map { if (it.isSameAs(train)) it.copy(notify = notify) else it },
    )

    fun addingNumber(number: String) = copy(numbers = (listOf(number) + numbers).distinct().take(MAX_NUMBERS))

    fun withoutNumber(number: String) = copy(numbers = numbers - number)

    fun without(train: RecentTrain) = copy(trains = trains - train)

    fun withoutFinishedTrains(now: LocalDateTime) = copy(trains = trains.filterNot { it.isOver(now) })

    fun without(station: RecentStation) = copy(stations = stations - station)

    fun without(journey: RecentJourney) = copy(journeys = journeys - journey)
}

/** Stored on the phone only. */
class RecentHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun load(): RecentHistory {
        val saved = preferences.getString(KEY, null) ?: return RecentHistory()
        return try {
            json.decodeFromString(saved)
        } catch (e: SerializationException) {
            RecentHistory()
        }
    }

    fun save(history: RecentHistory) {
        preferences.edit().putString(KEY, json.encodeToString(history)).apply()
    }

    private companion object {
        const val KEY = "history"
        val json = Json { ignoreUnknownKeys = true }
    }
}
