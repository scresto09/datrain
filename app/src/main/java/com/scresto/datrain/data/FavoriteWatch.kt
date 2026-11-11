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
    /** The full feed has been read for this run at least once. */
    val fullChecked: Boolean = false,
    /** When the full feed was last read for this run, ISO date-time; empty if never. */
    val fullAt: String = "",
    val alertsDay: String = "",
    /** When the alerts were last read for this run, ISO date-time; empty if never. */
    val alertsAt: String = "",
    /** In a journey, the change before this train was already reported at risk. */
    val connectionWarned: Boolean = false,
    /** Delays at the traveller's own departure and arrival stations, when read; otherwise [delayMinutes]. */
    val departureDelayMinutes: Int? = null,
    val arrivalDelayMinutes: Int? = null,
) {
    val departureDelay: Int get() = departureDelayMinutes ?: delayMinutes
    val arrivalDelay: Int get() = arrivalDelayMinutes ?: delayMinutes
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
    /** The first platform known, shortly before the departure: often what travellers wait for on the concourse. */
    data class PlatformAnnounced(val platform: String) : FavoriteChange
    data class NewAlert(val title: String) : FavoriteChange
    data class ConnectionAtRisk(val station: String, val minutes: Long) : FavoriteChange
}

enum class LiveCheck { NONE, FULL_FEED, STATION_BOARD }

object FavoriteWatch {
    private const val MIN_DELAY_CHANGE = 5
    private val ALERTS_BEFORE: Duration = Duration.ofHours(48)
    private val ALERTS_EVERY: Duration = Duration.ofMinutes(30)
    private val FULL_FEED_EVERY: Duration = Duration.ofHours(1)
    /** A platform that appears this close to the departure was announced late, as for long-distance trains. */
    private val PLATFORM_ANNOUNCED_BEFORE: Duration = Duration.ofHours(1)

    /**
     * The light station board covers about 3 hours, the heavy full feed about 6, and is used only once.
     * [force]: a refresh asked by the user reads what it can at once, whatever was read lately.
     */
    fun liveCheck(state: WatchState, now: LocalDateTime, force: Boolean = false): LiveCheck {
        val departure = state.departureTime
        return when {
            state.cancelled || now >= state.arrivalTime.plusMinutes(state.arrivalDelay.toLong()) -> LiveCheck.NONE
            now >= departure.minus(Board.HORIZON) -> LiveCheck.STATION_BOARD
            // Before boards can show the train, the full feed once an hour: a cancellation decided hours
            // ahead is seen within the hour, not only when the boards take over.
            now >= departure.minus(LiveFeed.HORIZON) &&
                (force || state.fullAt.isEmpty() || now >= LocalDateTime.parse(state.fullAt).plus(FULL_FEED_EVERY)) ->
                LiveCheck.FULL_FEED
            else -> LiveCheck.NONE
        }
    }

    /** Remembered state of each train of [favorite], under the keys the background check uses. */
    fun legKeys(favorite: FavoriteTrain): List<String> =
        if (favorite.isJourney) favorite.legs.indices.map { "${favorite.key}#$it" } else listOf(favorite.key)

    /** Minutes to change from [before] to [after], delays included, if a delay has made it risky. */
    fun connectionAtRisk(before: WatchState, after: WatchState, tightMinutes: Long): Long? {
        if (before.cancelled || after.cancelled || after.connectionWarned) return null
        val planned = Duration.between(before.arrivalTime, after.departureTime).toMinutes()
        val estimated = Duration.between(
            before.arrivalTime.plusMinutes(before.arrivalDelay.toLong()),
            after.departureTime.plusMinutes(after.departureDelay.toLong()),
        ).toMinutes()
        return estimated.takeIf { it < planned && it < tightMinutes }
    }

    /**
     * Messages are read once a day within two days of the run, and every half hour from the time boards
     * show the train until it arrives: an incident posted on the morning itself is seen before leaving.
     */
    fun needsAlerts(state: WatchState, now: LocalDateTime, force: Boolean = false): Boolean {
        val close = now >= state.departureTime.minus(Board.HORIZON) && now < state.arrivalTime.plusMinutes(state.arrivalDelay.toLong())
        if (close) return force || state.alertsAt.isEmpty() || now >= LocalDateTime.parse(state.alertsAt).plus(ALERTS_EVERY)
        return (force || state.alertsDay != now.toLocalDate().toString()) && now >= state.departureTime.minus(ALERTS_BEFORE)
    }

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
            // Going back to an earlier run (one still running late) says nothing about the later one.
            if (new.departureTime < old.departureTime) return emptyList()
            // A later run is expected once the previous one is over, not before.
            if (!old.cancelled && old.arrivalTime > now) return listOf(FavoriteChange.Removed(old.departureTime))
            // The next run taken over: what is already known about it is news, such as a strike notice
            // published the day before. Messages already told for the previous run are not repeated.
            if (new.cancelled) return listOf(FavoriteChange.Cancelled)
            return buildList {
                if (kotlin.math.abs(new.delayMinutes) >= MIN_DELAY_CHANGE) add(FavoriteChange.Delayed(new.delayMinutes))
                (new.alerts - old.alerts).forEach { add(FavoriteChange.NewAlert(it)) }
            }
        }
        if (new.cancelled) return if (old.cancelled) emptyList() else listOf(FavoriteChange.Cancelled)
        return buildList {
            if (old.departure != new.departure) add(FavoriteChange.ScheduleChanged(new.departureTime))
            if (kotlin.math.abs(new.delayMinutes - old.notifiedDelay) >= MIN_DELAY_CHANGE) add(FavoriteChange.Delayed(new.delayMinutes))
            when {
                old.platform != null && new.platform != null && old.platform != new.platform ->
                    add(FavoriteChange.PlatformChanged(new.platform))
                old.platform == null && new.platform != null && now >= new.departureTime.minus(PLATFORM_ANNOUNCED_BEFORE) &&
                    now < new.departureTime.plusMinutes(new.delayMinutes.toLong()) ->
                    add(FavoriteChange.PlatformAnnounced(new.platform))
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
            departureDelayMinutes = from?.let { it.departureDelaySeconds / 60 },
            arrivalDelayMinutes = to?.let { it.arrivalDelaySeconds / 60 },
            platform = from?.platform ?: state.platform,
        )
    }

    /** From SNCF's own feed, for a train the live feed does not have; unchanged when it has none either. */
    fun withUpdates(state: WatchState, updates: List<RealtimeTrip>, ride: RideLeg): WatchState {
        val checked = state.copy(fullChecked = true)
        val date = ride.trip.serviceDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        val numbers = TimetableMerger.numbers(ride.trip.number)
        if (updates.none { it.number in numbers && it.startDate == date }) return checked
        val trip = RealtimeOverlay.apply(listOf(Itinerary(listOf(ride))), null, updates).single().rides.single().trip
        return checked.copy(
            cancelled = trip.cancelled,
            delayMinutes = trip.arrivalDelayMinutes.takeIf { it != 0 } ?: trip.departureDelayMinutes,
            departureDelayMinutes = trip.departureDelayMinutes,
            arrivalDelayMinutes = trip.arrivalDelayMinutes,
        )
    }

    /** [boarding]: read at the traveller's departure station, otherwise at their arrival station. */
    /**
     * Null when the board cannot tell: out of date, another day, or without the train. After midnight,
     * a train of the day before is on the new day's board, its times counted from that new midnight.
     */
    fun withBoard(state: WatchState, board: Board, ride: RideLeg, boarding: Boolean, now: java.time.OffsetDateTime): WatchState? {
        val trip = ride.trip
        val planned = Duration.between(trip.serviceDate.atStartOfDay(), if (boarding) trip.departure else trip.arrival).seconds.toInt()
        val passage = BoardPatch.find(
            board, trip.number, trip.serviceDate,
            departure = planned.takeIf { boarding }, arrival = planned.takeUnless { boarding }, now = now,
        ) ?: return null
        return if (boarding) {
            state.copy(
                cancelled = passage.cancelled,
                delayMinutes = passage.departureDelaySeconds / 60,
                departureDelayMinutes = passage.departureDelaySeconds / 60,
                platform = passage.platform ?: state.platform,
            )
        } else {
            state.copy(
                cancelled = passage.cancelled,
                delayMinutes = passage.arrivalDelaySeconds / 60,
                arrivalDelayMinutes = passage.arrivalDelaySeconds / 60,
            )
        }
    }

    /**
     * Before boarding, the arrival station's board as well, once it shows the train: the delay told stays
     * the departure one, but the estimated arrival is known too. Null when the board cannot tell.
     */
    fun withArrivalBoard(state: WatchState, board: Board, ride: RideLeg, now: java.time.OffsetDateTime): WatchState? =
        withBoard(state, board, ride, boarding = false, now)?.let {
            state.copy(cancelled = state.cancelled || it.cancelled, arrivalDelayMinutes = it.arrivalDelayMinutes)
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
        preferences.edit().putString(KEY, json.encodeToString(states)).putLong(CHECKED_AT, System.currentTimeMillis()).apply()
    }

    /** When the background check last ran, in milliseconds; 0 if never. */
    fun checkedAt(): Long = preferences.getLong(CHECKED_AT, 0)

    /** Runs already looked for after their planned arrival, as "favorite key@departure". */
    fun lateChecked(): Set<String> = preferences.getStringSet(LATE_CHECKED, emptySet()).orEmpty()

    fun saveLateChecked(ids: Set<String>) {
        preferences.edit().putStringSet(LATE_CHECKED, ids).apply()
    }

    /** A refresh asked from the widget, so that it can show at once that it is on its way. */
    fun markRefreshAsked() {
        preferences.edit().putLong(REFRESH_ASKED_AT, System.currentTimeMillis()).apply()
    }

    /**
     * Asked since the last check, and recently: past that, the request is taken as lost (no network).
     * Shown for at least a second after the tap, even when the check is over sooner.
     */
    fun isRefreshing(): Boolean {
        val asked = preferences.getLong(REFRESH_ASKED_AT, 0)
        val since = System.currentTimeMillis() - asked
        return (asked > checkedAt() || since < REFRESH_SHOWN_MILLIS) && since < REFRESH_PATIENCE_MILLIS
    }

    private companion object {
        const val KEY = "states"
        const val CHECKED_AT = "checkedAt"
        // Renamed when marks also went on runs still going: those old marks are dropped.
        const val LATE_CHECKED = "lateRunsDone"
        const val REFRESH_ASKED_AT = "refreshAskedAt"
        const val REFRESH_PATIENCE_MILLIS = 2 * 60_000L
        const val REFRESH_SHOWN_MILLIS = 1_000L
    }
}
