package com.scresto.datrain.data

import android.content.Context
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDateTime

/** What was last known about a favorite's next run. Times are ISO local date-times, Paris time. */
@Serializable
data class WatchState(
    val departure: String,
    val arrival: String,
    val delayMinutes: Int = 0,
    /** Last delay the user was told about: small changes add up until they are worth telling. */
    val notifiedDelay: Int = 0,
    val cancelled: Boolean = false,
    val platform: String? = null,
    val alerts: Set<String> = emptySet(),
    /** The one full feed check of this run is done. */
    val fullChecked: Boolean = false,
    val alertsDay: String = "",
    /** In a journey, the change before this train was already reported at risk. */
    val connectionWarned: Boolean = false,
) {
    val date: String get() = departure.substringBefore('T')
    val departureTime: LocalDateTime get() = LocalDateTime.parse(departure)
    val arrivalTime: LocalDateTime get() = LocalDateTime.parse(arrival)
}

sealed interface FavoriteChange {
    data object Cancelled : FavoriteChange
    /** The run left the timetables: this is how cancellations known in advance show up. */
    data class Removed(val departure: LocalDateTime) : FavoriteChange
    data class ScheduleChanged(val departure: LocalDateTime) : FavoriteChange
    data class Delayed(val minutes: Int) : FavoriteChange
    data class PlatformChanged(val platform: String) : FavoriteChange
    data class NewAlert(val title: String) : FavoriteChange
    data class ConnectionAtRisk(val station: String, val minutes: Long) : FavoriteChange
}

enum class LiveCheck { NONE, FULL_FEED, STATION_BOARD }

object FavoriteWatch {
    private const val MIN_DELAY_CHANGE = 5
    private val FULL_FEED_BEFORE: Duration = Duration.ofHours(6)
    private val BOARD_BEFORE: Duration = Duration.ofHours(3)
    private val ALERTS_BEFORE: Duration = Duration.ofHours(48)

    /** The light station board covers about 3 hours, the heavy full feed about 6, and is used only once. */
    fun liveCheck(state: WatchState, now: LocalDateTime): LiveCheck {
        val departure = state.departureTime
        return when {
            state.cancelled || now >= state.arrivalTime.plusMinutes(state.delayMinutes.toLong()) -> LiveCheck.NONE
            now >= departure.minus(BOARD_BEFORE) -> LiveCheck.STATION_BOARD
            now >= departure.minus(FULL_FEED_BEFORE) && !state.fullChecked -> LiveCheck.FULL_FEED
            else -> LiveCheck.NONE
        }
    }

    /** Remembered state of each train of [favorite], under the keys the background check uses. */
    fun legKeys(favorite: FavoriteTrain): List<String> =
        if (favorite.isJourney) favorite.legs.indices.map { "${favorite.key}#$it" } else listOf(favorite.key)

    /**
     * Where to look for the run to watch: now, or just before the last train's planned arrival
     * while it is known to be late and not there yet, so that the late run stays watched.
     */
    fun watchedFrom(favorite: FavoriteTrain, states: Map<String, WatchState>, now: LocalDateTime): LocalDateTime {
        val last = states[legKeys(favorite).last()] ?: return now
        val stillLate = !last.cancelled && last.arrivalTime <= now &&
            last.arrivalTime.plusMinutes(last.delayMinutes.toLong()) > now
        return if (stillLate) last.arrivalTime.minusMinutes(1) else now
    }

    /** Minutes to change from [before] to [after], delays included, if a delay has made it risky. */
    fun connectionAtRisk(before: WatchState, after: WatchState, tightMinutes: Long): Long? {
        if (before.cancelled || after.cancelled || after.connectionWarned) return null
        val planned = Duration.between(before.arrivalTime, after.departureTime).toMinutes()
        val estimated = Duration.between(
            before.arrivalTime.plusMinutes(before.delayMinutes.toLong()),
            after.departureTime.plusMinutes(after.delayMinutes.toLong()),
        ).toMinutes()
        return estimated.takeIf { it < planned && it < tightMinutes }
    }

    fun needsAlerts(state: WatchState, now: LocalDateTime): Boolean =
        state.alertsDay != now.toLocalDate().toString() && now >= state.departureTime.minus(ALERTS_BEFORE)

    /** Real-time knowledge is kept as long as this is still the same run. */
    fun stateFor(ride: RideLeg, old: WatchState?): WatchState {
        val departure = ride.trip.departure.toString()
        val arrival = ride.trip.arrival.toString()
        return if (old != null && old.date == departure.substringBefore('T')) {
            old.copy(departure = departure, arrival = arrival)
        } else {
            WatchState(departure, arrival)
        }
    }

    fun changes(old: WatchState?, new: WatchState, now: LocalDateTime): List<FavoriteChange> {
        // A first look only sets the reference, but a train already cancelled must be told.
        if (old == null) return if (new.cancelled) listOf(FavoriteChange.Cancelled) else emptyList()
        if (old.date != new.date) {
            // A later run is expected once the previous one is over, not before.
            return if (!old.cancelled && old.arrivalTime > now) listOf(FavoriteChange.Removed(old.departureTime)) else emptyList()
        }
        if (new.cancelled) return if (old.cancelled) emptyList() else listOf(FavoriteChange.Cancelled)
        return buildList {
            if (old.departure != new.departure) add(FavoriteChange.ScheduleChanged(new.departureTime))
            if (kotlin.math.abs(new.delayMinutes - old.notifiedDelay) >= MIN_DELAY_CHANGE) add(FavoriteChange.Delayed(new.delayMinutes))
            if (old.platform != null && new.platform != null && old.platform != new.platform) {
                add(FavoriteChange.PlatformChanged(new.platform))
            }
            (new.alerts - old.alerts).forEach { add(FavoriteChange.NewAlert(it)) }
        }
    }

    /** What to remember after this check. A first look only sets the reference. */
    fun remembered(old: WatchState?, new: WatchState, changes: List<FavoriteChange>): WatchState =
        if (old == null || old.date != new.date || changes.any { it is FavoriteChange.Delayed }) {
            new.copy(notifiedDelay = new.delayMinutes)
        } else {
            new
        }

    /** Today's run cancelled in advance: it has left the timetables, only the live feed still knows it. */
    fun missingRun(favorite: FavoriteTrain, ride: RideLeg?, feed: LiveFeed, now: LocalDateTime): RideLeg? {
        val query = CancelledQuery(
            Station(favorite.fromName, favorite.fromUic, -1),
            Station(favorite.toName, favorite.toUic, -1),
            now.toLocalDate(),
            0,
            arriveBy = false,
        )
        val shown = ride?.takeIf { it.trip.serviceDate == now.toLocalDate() }?.let { listOf(Itinerary(listOf(it))) }.orEmpty()
        val numbers = TimetableMerger.numbers(favorite.number)
        return RealtimeOverlay.cancelledMissing(feed, query, shown)
            .flatMap { it.rides }
            .firstOrNull { it.trip.number in numbers && it.trip.arrival > now }
    }

    /** The run the feed says is cancelled, whether or not it is still in the timetables. */
    fun cancelledRun(favorite: FavoriteTrain, ride: RideLeg?, feed: LiveFeed, now: LocalDateTime): RideLeg? =
        missingRun(favorite, ride, feed, now) ?: ride?.takeIf { feed.find(it.trip)?.cancelled == true }

    fun withFeed(state: WatchState, feed: LiveFeed, ride: RideLeg): WatchState {
        val checked = state.copy(fullChecked = true)
        val train = feed.find(ride.trip) ?: return checked
        val from = train.stops.firstOrNull { it.uic == ride.trip.fromKey }
        val to = train.stops.lastOrNull { it.uic == ride.trip.toKey }
        return checked.copy(
            cancelled = train.cancelled || from?.removed == true || to?.removed == true,
            delayMinutes = (to?.arrivalDelaySeconds ?: from?.departureDelaySeconds ?: 0) / 60,
            platform = from?.platform ?: state.platform,
        )
    }

    /** [boarding]: read at the traveller's departure station, otherwise at their arrival station. */
    fun withBoard(state: WatchState, board: Board, ride: RideLeg, boarding: Boolean, now: java.time.OffsetDateTime): WatchState {
        if (now >= board.staleAfter || board.day != ride.trip.serviceDate) return state
        val numbers = TimetableMerger.numbers(ride.trip.number)
        val midnight = ride.trip.serviceDate.atStartOfDay()
        val planned = Duration.between(midnight, if (boarding) ride.trip.departure else ride.trip.arrival).seconds.toInt()
        val candidates = board.passages.filter { it.number in numbers }
        val passage = candidates.firstOrNull { (if (boarding) it.departureSeconds else it.arrivalSeconds) == planned }
            ?: candidates.singleOrNull()
            ?: return state
        return state.copy(
            cancelled = passage.cancelled,
            delayMinutes = (if (boarding) passage.departureDelaySeconds else passage.arrivalDelaySeconds) / 60,
            platform = if (boarding) passage.platform ?: state.platform else state.platform,
        )
    }
}

class FavoriteWatchStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Map<String, WatchState> {
        val saved = preferences.getString(KEY, null) ?: return emptyMap()
        return try {
            json.decodeFromString(saved)
        } catch (e: SerializationException) {
            emptyMap()
        }
    }

    fun save(states: Map<String, WatchState>) {
        preferences.edit().putString(KEY, json.encodeToString(states)).apply()
    }

    private companion object {
        const val KEY = "states"
    }
}
