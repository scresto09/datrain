package com.scresto.datrain.data

import java.io.IOException
import java.time.LocalDateTime

/** Where the favorite check reads real time: the network in the app, prepared answers in tests. */
interface LiveSource {
    suspend fun feed(): LiveFeed
    suspend fun board(uic: Int): Board
    suspend fun alerts(): List<ServiceAlert>
    /** SNCF's own feed, for a train the live feed does not have. */
    suspend fun tripUpdates(): List<RealtimeTrip>
}

/** A notification to send: about [train], opening the favorite [opens] (a whole journey for one of its trains). */
data class Notice(val train: FavoriteTrain, val departure: LocalDateTime, val changes: List<FavoriteChange>, val opens: String)

class CheckResult(
    val states: Map<String, WatchState>,
    /** Late runs already looked for, as "favorite key@departure". */
    val lateChecked: Set<String>,
    val notices: List<Notice>,
    /** Planned and estimated departures, and estimated arrivals, of the runs followed, for the alarms around them. */
    val departures: List<LocalDateTime>,
    val arrivals: List<LocalDateTime>,
    /** Estimated departure and arrival of each run followed that is not cancelled, for the checks on board. */
    val trips: List<Pair<LocalDateTime, LocalDateTime>> = emptyList(),
)

/**
 * One pass over the watched favorites, without Android: which run to follow, what real time says about it,
 * and what changed since last time. The background check stores the result, sends the notices and sets
 * the alarms.
 */
object FavoriteCheck {
    /** Same threshold as "Correspondance menacée" on the results. */
    const val TIGHT_CONNECTION_MINUTES = 5L

    suspend fun run(
        favorites: List<FavoriteTrain>,
        tells: (FavoriteTrain) -> Boolean,
        planner: JourneyPlanner,
        saved: Map<String, WatchState>,
        lateChecked: Set<String>,
        live: LiveSource,
        now: LocalDateTime,
        force: Boolean = false,
        fullFeed: Boolean = false,
    ): CheckResult {
        val states = saved.toMutableMap()
        val checked = lateChecked.toMutableSet()
        val notices = ArrayList<Notice>()
        val departures = ArrayList<LocalDateTime>()
        val arrivals = ArrayList<LocalDateTime>()
        val trips = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
        for (favorite in favorites) {
            val tell = tells(favorite)
            val candidates = FavoriteRuns.candidates(favorite, planner, now)
            val last = states[FavoriteWatch.legKeys(favorite).last()]
            val late = candidates.late
            // The full feed only when the saved state cannot tell about a late run, and once per such run.
            val lateId = late?.let { "${favorite.key}@${it.first().trip.departure}" }
            val feed = if (late != null && !FavoriteRuns.knows(late, last) && lateId !in checked) {
                try {
                    live.feed()
                } catch (e: NomadeError) {
                    null
                } catch (e: IOException) {
                    null
                }
            } else {
                null
            }
            val rides = FavoriteRuns.choose(candidates, feed, last, now)
            // Arrived, or unknown to the feed: not looked for again. A run still going is followed by its state.
            if (feed != null && rides !== late) checked += lateId!!
            rides?.firstOrNull()?.let { departures += it.trip.departure }
            // A journey is watched train by train, then its changes.
            val legStates = favorite.legs.mapIndexed { i, leg ->
                val key = FavoriteWatch.legKeys(favorite)[i]
                val old = states[key]
                val state = try {
                    read(leg, old, rides?.getOrNull(i), live, now, force, fullFeed)
                } catch (e: NomadeError) {
                    null
                } catch (e: IOException) {
                    null
                } ?: return@mapIndexed null
                val changes = FavoriteWatch.changes(old, state, now)
                if (tell && changes.isNotEmpty()) notices += Notice(leg, state.departureTime, changes, favorite.key)
                FavoriteWatch.remembered(old, state, changes).also { states[key] = it }
            }
            legStates.lastOrNull()?.takeUnless { it.cancelled }?.let { arrivals += it.arrivesAt }
            // The alarms before the departure follow its delay too: a late train is checked up to when it really leaves.
            val leaving = legStates.firstOrNull()?.takeUnless { it.cancelled }
            leaving?.let { departures += it.leavesAt }
            val arriving = legStates.lastOrNull()?.takeUnless { it.cancelled }
            if (leaving != null && arriving != null && legStates.none { it?.cancelled == true }) trips += leaving.leavesAt to arriving.arrivesAt
            legStates.zipWithNext().forEachIndexed { i, (before, after) ->
                if (before == null || after == null) return@forEachIndexed
                val minutes = FavoriteWatch.connectionAtRisk(before, after, TIGHT_CONNECTION_MINUTES) ?: return@forEachIndexed
                val next = favorite.legs[i + 1]
                if (tell) notices += Notice(next, after.departureTime, listOf(FavoriteChange.ConnectionAtRisk(next.fromName, minutes)), favorite.key)
                states[FavoriteWatch.legKeys(favorite)[i + 1]] = after.copy(connectionWarned = true)
            }
        }
        val kept = states.filterKeys { key -> favorites.any { key == it.key || key.startsWith(it.key + "#") } }
        return CheckResult(kept, checked, notices, departures, arrivals, trips)
    }

    /**
     * What real time says about [ride] of [favorite], from [old] if it is the same run. Null when there is
     * nothing to watch. A first look reads the full feed once, because a run cancelled in advance has left
     * the timetables. It throws without network, to be tried again. [force]: asked by the user, see
     * [FavoriteWatch.liveCheck]. [fullFeed]: the full feed in place of the station boards, as the favorites
     * list reads it: a board gives only one of the two delays, the feed both, from the same data.
     */
    suspend fun read(
        favorite: FavoriteTrain,
        old: WatchState?,
        ride: RideLeg?,
        live: LiveSource,
        now: LocalDateTime,
        force: Boolean = false,
        fullFeed: Boolean = false,
    ): WatchState? {
        val cancelled = if (old == null) FavoriteWatch.cancelledRun(favorite, ride, live.feed(), now) else null
        if (cancelled != null) return FavoriteWatch.stateFor(cancelled, null).copy(cancelled = true)
        // No run left at all: the expected one is gone.
        if (ride == null) return if (old != null && old.arrivalTime > now) old.copy(cancelled = true) else null
        var state = FavoriteWatch.stateFor(ride, old)
        // First look at a run that leaves within hours, or is already going (a late one after its planned
        // arrival included): its delay from the live feed at once, rather than none.
        val firstLook = old == null || old.date != state.date
        if (firstLook && now >= state.departureTime.minus(LiveFeed.HORIZON) && live.feed().find(ride.trip) != null) {
            state = FavoriteWatch.withFeed(state, live.feed(), ride).copy(fullAt = now.toString())
        }
        try {
            when (FavoriteWatch.liveCheck(state, now, force)) {
                LiveCheck.NONE -> Unit
                LiveCheck.FULL_FEED -> state = withLive(state, live, ride).copy(fullAt = now.toString())
                LiveCheck.STATION_BOARD -> state = if (fullFeed) {
                    withLive(state, live, ride).copy(fullAt = now.toString())
                } else {
                    withBoards(state, favorite, ride, live, now)
                }
            }
            if (FavoriteWatch.needsAlerts(state, now, force)) {
                val alerts = TrainAlerts.of(live.alerts(), ride.trip)
                val titles = alerts.train + alerts.byStation[ride.trip.fromKey].orEmpty() + alerts.byStation[ride.trip.toKey].orEmpty()
                state = state.copy(alerts = titles.map { it.title }.toSet(), alertsDay = now.toLocalDate().toString(), alertsAt = now.toString())
            }
        } catch (e: NomadeError) {
            // Next time: this check is simply skipped.
        } catch (e: IOException) {
            // Same.
        }
        return state
    }

    /**
     * Real time from the light station boards. Before boarding, the departure board, then the arrival board
     * once it shows the train; on board, the arrival board. A board that cannot tell, or no station code, gives
     * way to the full feed. When the departure delay moved and no board can tell the arrival yet, the full feed
     * gives both, from the same data, rather than an arrival left as it was. Throws when neither the first
     * board nor the full feed in its place can be read.
     */
    private suspend fun withBoards(state: WatchState, favorite: FavoriteTrain, ride: RideLeg, live: LiveSource, now: LocalDateTime): WatchState {
        val boarding = now < state.leavesAt
        val at = now.atZone(PARIS).toOffsetDateTime()
        // The code from today's timetables first: a favorite saved before a station's code was known keeps a 0.
        val uic = (if (boarding) ride.trip.fromKey else ride.trip.toKey).toIntOrNull()
            ?: (if (boarding) favorite.fromUic else favorite.toUic)
        val fromBoard = if (uic != 0) FavoriteWatch.withBoard(state, live.board(uic), ride, boarding, at) else null
        val read = fromBoard ?: withLive(state, live, ride)
        if (!boarding || read.cancelled) return read
        val arrivalUic = ride.trip.toKey.toIntOrNull() ?: favorite.toUic
        if (arrivalUic != 0 && now >= read.arrivesAt.minus(Board.HORIZON)) {
            orNull { FavoriteWatch.withArrivalBoard(read, live.board(arrivalUic), ride, at) }?.let { return it }
        }
        if (fromBoard == null || read.departureDelay == state.departureDelay) return read
        return orNull { withLive(read, live, ride).copy(fullAt = now.toString()) } ?: read
    }

    /** Null when real time cannot be read this time. */
    private inline fun <T> orNull(read: () -> T): T? = try {
        read()
    } catch (e: NomadeError) {
        null
    } catch (e: IOException) {
        null
    }

    /** The live feed, or SNCF's own feed for a train the live feed does not have, as on the favorites list. */
    private suspend fun withLive(state: WatchState, live: LiveSource, ride: RideLeg): WatchState {
        val feed = live.feed()
        if (feed.find(ride.trip) != null) return FavoriteWatch.withFeed(state, feed, ride)
        return FavoriteWatch.withUpdates(state, live.tripUpdates(), ride)
    }
}
