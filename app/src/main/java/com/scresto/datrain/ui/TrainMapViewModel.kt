package com.scresto.datrain.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.scresto.datrain.data.AlertsCache
import com.scresto.datrain.data.Http
import com.scresto.datrain.data.LatLon
import com.scresto.datrain.data.BoardPatch
import com.scresto.datrain.data.LiveStop
import com.scresto.datrain.data.LiveTrain
import com.scresto.datrain.data.NomadeApi
import com.scresto.datrain.data.NomadeError
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.ProgressCalculator
import com.scresto.datrain.data.TrainAlerts
import com.scresto.datrain.data.TrainProgress
import com.scresto.datrain.data.TrainRoute
import com.scresto.datrain.data.Trip
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime

sealed interface TrainStatus {
    data object Loading : TrainStatus
    data class Failed(val message: String) : TrainStatus
    data object NotTracked : TrainStatus
    data object Cancelled : TrainStatus
    data object Stale : TrainStatus
    data class Live(val progress: TrainProgress) : TrainStatus
}

data class TrainMapState(
    val trip: Trip? = null,
    val status: TrainStatus = TrainStatus.Loading,
    val train: LiveTrain? = null,
    val stops: List<LiveStop> = emptyList(),
    val route: TrainRoute? = null,
    val position: LatLon? = null,
    val collectedAt: OffsetDateTime? = null,
    /** Seconds from midnight of the train's service day. */
    val nowSeconds: Int = 0,
    val alerts: TrainAlerts = TrainAlerts(),
    val alertsFailed: Boolean = false,
)

class TrainMapViewModel(application: Application) : AndroidViewModel(application) {
    private val api = NomadeApi(Http.client(application))
    private val alertsCache = AlertsCache.get(application)
    private var lastError: String? = null
    /** At least one full read has answered. */
    private var loaded = false
    /** The train from the full feed, kept up to date with station boards in between. */
    private var train: LiveTrain? = null
    private var freshUntil: OffsetDateTime? = null
    private var collectedAt: OffsetDateTime? = null
    private var lastFullRead = 0L

    private val _state = MutableStateFlow(TrainMapState())
    val state: StateFlow<TrainMapState> = _state.asStateFlow()

    private val _others = MutableStateFlow<Map<String, TrainRoute>>(emptyMap())
    /** Planned routes of the journey's other trains, by [routeKey]. Kept when switching between them. */
    val others: StateFlow<Map<String, TrainRoute>> = _others.asStateFlow()

    suspend fun loadOthers(trips: List<Trip>) {
        for (trip in trips) {
            val key = routeKey(trip)
            if (key in _others.value) continue
            val stops = plannedTrain(trip)?.activeStops ?: continue
            try {
                val route = api.buildRoute(stops, trip.plannedStations, trip.plannedStops)
                _others.update { it + (key to route) }
            } catch (e: NomadeError) {
                // Only the followed train is drawn.
            }
        }
    }

    /** Runs while the screen is visible: the caller cancels the coroutine when it no longer is. */
    suspend fun follow(trip: Trip) {
        if (_state.value.trip != trip) {
            lastError = null
            loaded = false
            train = null
            freshUntil = null
            collectedAt = null
            lastFullRead = 0L
            _state.value = TrainMapState(trip = trip)
        }
        coroutineScope {
            // Separate from the position, which must not wait for this heavier feed.
            launch {
                while (true) {
                    loadAlerts(trip)
                    delay(ALERTS_REFRESH_MILLIS)
                }
            }
            var nextFetch = 0L
            while (true) {
                val clock = System.currentTimeMillis()
                if (clock >= nextFetch) {
                    refresh(trip)
                    nextFetch = clock + REFRESH_MILLIS
                }
                recompute(trip)
                delay(TICK_MILLIS)
            }
        }
    }

    private suspend fun loadAlerts(trip: Trip) {
        try {
            val alerts = TrainAlerts.of(alertsCache.get(), trip)
            _state.update { it.copy(alerts = alerts, alertsFailed = false) }
        } catch (e: NomadeError) {
            _state.update { it.copy(alertsFailed = true) }
        }
    }

    /**
     * Each minute, only the boards of the next station and of the traveller's own (about 1.4 KB each).
     * The full feed (about 270 KB) every 10 minutes, or as soon as a board cannot be trusted.
     */
    private suspend fun refresh(trip: Trip) {
        try {
            val due = System.currentTimeMillis() - lastFullRead >= FULL_READ_MILLIS
            val current = train
            if (current == null || !current.hasRealtime) {
                if (due) readFull(trip)
            } else if (due || !readBoards(trip, current)) {
                readFull(trip)
            }
        } catch (e: NomadeError) {
            lastError = when (e) {
                NomadeError.RateLimited -> "Trop de requêtes envoyées à l'API. Nouvel essai dans une minute."
                else -> "Impossible d'obtenir les données en direct. Nouvel essai dans une minute."
            }
        }
    }

    private suspend fun readFull(trip: Trip) {
        val latest = api.liveFeed()
        lastFullRead = System.currentTimeMillis()
        lastError = null
        loaded = true
        train = latest.find(trip)
        freshUntil = latest.staleAfter
        collectedAt = latest.collectedAt
        val shown = train?.takeUnless { OffsetDateTime.now(PARIS) >= latest.staleAfter } ?: plannedTrain(trip)
        if (shown != null && !shown.cancelled) {
            // Served stops can change (cancelled stop): the route follows.
            if (_state.value.route?.fits(shown) != true) {
                val route = api.buildRoute(shown.activeStops, trip.plannedStations, shown.stops)
                _state.update { it.copy(route = route, stops = shown.activeStops) }
            }
        }
    }

    /** False when only the full feed can tell: train missing from a board, cancelled, or stale board. */
    private suspend fun readBoards(trip: Trip, current: LiveTrain): Boolean {
        val elapsed = secondsSinceServiceStart(current)
        val active = current.activeStops
        val next = when (val progress = ProgressCalculator.compute(active, elapsed)) {
            TrainProgress.NotStarted -> 0
            is TrainProgress.AtStop -> progress.index
            is TrainProgress.Between -> progress.to
            TrainProgress.Arrived -> return true
        }
        // The traveller's stations still ahead: where they board, then where they get off.
        val boarding = active.indexOfFirst { it.uic == trip.fromKey }.takeIf { it > next }
        val alighting = active.indexOfLast { it.uic == trip.toKey }.takeIf { it > next }
        var updated = current
        var until: OffsetDateTime? = null
        for (stop in listOfNotNull(next, boarding, alighting).distinct().mapNotNull(active::getOrNull)) {
            val expected = stop.estimatedArrival ?: stop.estimatedDeparture ?: continue
            // Beyond what a board shows, the last full read stays the source.
            if (expected - elapsed > BOARD_HORIZON_SECONDS) continue
            val board = api.board(stop.uic)
            if (OffsetDateTime.now(PARIS) >= board.staleAfter || board.day != current.serviceDate) return false
            val passage = BoardPatch.find(board, current, stop) ?: return false
            if (passage.cancelled) return false
            updated = BoardPatch.apply(updated, current.stops.indexOf(stop), passage)
            until = listOfNotNull(until, board.staleAfter).min()
        }
        if (until != null) {
            train = updated
            freshUntil = until
            collectedAt = OffsetDateTime.now(PARIS)
        }
        return true
    }

    private fun secondsSinceServiceStart(train: LiveTrain): Int =
        Duration.between(train.serviceDate.atStartOfDay(), LocalDateTime.now(PARIS)).seconds.toInt()

    /** Outside live tracking, the train is still placed from its planned times. */
    private fun plannedTrain(trip: Trip): LiveTrain? =
        trip.plannedStops.takeIf { it.isNotEmpty() }?.let {
            LiveTrain(trip.number, trip.number, trip.serviceDate, cancelled = false, hasRealtime = false, stops = it)
        }

    private fun recompute(trip: Trip) {
        if (!loaded) {
            lastError?.let { message -> _state.update { it.copy(status = TrainStatus.Failed(message)) } }
            return
        }
        val isStale = freshUntil?.let { OffsetDateTime.now(PARIS) >= it } == true
        val train = this.train?.takeUnless { isStale } ?: plannedTrain(trip)
        val elapsed = Duration.between((train?.serviceDate ?: trip.serviceDate).atStartOfDay(), LocalDateTime.now(PARIS)).seconds.toInt()
        val status: TrainStatus = when {
            train == null -> if (isStale) TrainStatus.Stale else TrainStatus.NotTracked
            train.cancelled -> TrainStatus.Cancelled
            _state.value.route == null -> lastError?.let { TrainStatus.Failed(it) } ?: TrainStatus.Loading
            else -> TrainStatus.Live(ProgressCalculator.compute(train.activeStops, elapsed))
        }
        _state.update {
            it.copy(
                status = status,
                train = train,
                stops = train?.activeStops ?: it.stops,
                collectedAt = collectedAt,
                nowSeconds = elapsed,
                position = (status as? TrainStatus.Live)?.let { live -> it.route?.positionOf(live.progress) },
            )
        }
    }

    private companion object {
        const val REFRESH_MILLIS = 60_000L
        const val FULL_READ_MILLIS = 10 * 60_000L
        const val ALERTS_REFRESH_MILLIS = 15 * 60_000L
        /** A station board shows about 3 hours ahead. */
        const val BOARD_HORIZON_SECONDS = 3 * 3600
        const val TICK_MILLIS = 1_000L
    }
}

fun routeKey(trip: Trip) = "${trip.number}@${trip.departure}"
