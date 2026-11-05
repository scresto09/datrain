package com.scresto.datrain.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.scresto.datrain.data.Http
import com.scresto.datrain.data.LatLon
import com.scresto.datrain.data.LiveFeed
import com.scresto.datrain.data.LiveStop
import com.scresto.datrain.data.LiveTrain
import com.scresto.datrain.data.NomadeApi
import com.scresto.datrain.data.NomadeError
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.ProgressCalculator
import com.scresto.datrain.data.TrainProgress
import com.scresto.datrain.data.TrainRoute
import com.scresto.datrain.data.Trip
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
)

class TrainMapViewModel(application: Application) : AndroidViewModel(application) {
    private val api = NomadeApi(Http.client(application))
    private var feed: LiveFeed? = null
    private var lastError: String? = null

    private val _state = MutableStateFlow(TrainMapState())
    val state: StateFlow<TrainMapState> = _state.asStateFlow()

    /** Runs while the screen is visible: the caller cancels the coroutine when it no longer is. */
    suspend fun follow(trip: Trip) {
        if (_state.value.trip != trip) {
            feed = null
            lastError = null
            _state.value = TrainMapState(trip = trip)
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

    private suspend fun refresh(trip: Trip) {
        try {
            val latest = api.liveFeed()
            feed = latest
            lastError = null
            val train = latest.find(trip)?.takeUnless { OffsetDateTime.now(PARIS) >= latest.staleAfter } ?: plannedTrain(trip)
            if (train != null && !train.cancelled) {
                val previous = _state.value
                // Served stops can change (cancelled stop): the route follows.
                if (previous.route == null || previous.stops.map { it.uic } != train.activeStops.map { it.uic }) {
                    val route = api.buildRoute(train.activeStops, trip.plannedStations)
                    _state.update { it.copy(route = route, stops = train.activeStops) }
                }
            }
        } catch (e: NomadeError) {
            lastError = when (e) {
                NomadeError.RateLimited -> "Trop de requêtes envoyées à l'API. Nouvel essai dans une minute."
                else -> "Impossible d'obtenir les données en direct. Nouvel essai dans une minute."
            }
        }
    }

    /** Outside live tracking, the train is still placed from its planned times. */
    private fun plannedTrain(trip: Trip): LiveTrain? =
        trip.plannedStops.takeIf { it.isNotEmpty() }?.let {
            LiveTrain(trip.number, trip.number, trip.serviceDate, cancelled = false, hasRealtime = false, stops = it)
        }

    private fun recompute(trip: Trip) {
        val current = feed
        if (current == null) {
            lastError?.let { message -> _state.update { it.copy(status = TrainStatus.Failed(message)) } }
            return
        }
        val isStale = OffsetDateTime.now(PARIS) >= current.staleAfter
        val train = current.find(trip)?.takeUnless { isStale } ?: plannedTrain(trip)
        val status: TrainStatus = when {
            train == null -> if (isStale) TrainStatus.Stale else TrainStatus.NotTracked
            train.cancelled -> TrainStatus.Cancelled
            _state.value.route == null -> lastError?.let { TrainStatus.Failed(it) } ?: TrainStatus.Loading
            else -> {
                val elapsed = Duration.between(train.serviceDate.atStartOfDay(), LocalDateTime.now(PARIS)).seconds.toInt()
                TrainStatus.Live(ProgressCalculator.compute(train.activeStops, elapsed))
            }
        }
        _state.update {
            it.copy(
                status = status,
                train = train,
                stops = train?.activeStops ?: it.stops,
                collectedAt = current.collectedAt,
                position = (status as? TrainStatus.Live)?.let { live -> it.route?.positionOf(live.progress) },
            )
        }
    }

    private companion object {
        const val REFRESH_MILLIS = 60_000L
        const val TICK_MILLIS = 1_000L
    }
}
