package com.scresto.datrain.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scresto.datrain.data.GtfsRealtimeApi
import com.scresto.datrain.data.Http
import com.scresto.datrain.data.Itinerary
import com.scresto.datrain.data.JourneyPlanner
import com.scresto.datrain.data.LiveFeed
import com.scresto.datrain.data.NomadeApi
import com.scresto.datrain.data.NomadeError
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.RealtimeOverlay
import com.scresto.datrain.data.RealtimeTrip
import com.scresto.datrain.data.ServiceAlert
import com.scresto.datrain.data.Station
import com.scresto.datrain.data.TimetableRepository
import com.scresto.datrain.data.TimetableState
import com.scresto.datrain.data.Trip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class UiState(
    val timetable: TimetableState = TimetableState.Loading,
    val fromText: String = "",
    val toText: String = "",
    val from: Station? = null,
    val to: Station? = null,
    val date: LocalDate,
    val time: LocalTime,
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val itineraries: List<Itinerary> = emptyList(),
    val nextDay: LocalDate? = null,
    val realtimeUnavailable: Boolean = false,
    val error: String? = null,
    val trackedTrip: Trip? = null,
    val showSources: Boolean = false,
    /** Alerts per journey, once its details are open. */
    val alerts: Map<String, List<ServiceAlert>> = emptyMap(),
    val alertsFailed: Set<String> = emptySet(),
) {
    val sameStation: Boolean get() = from != null && from.name == to?.name
    val canSearch: Boolean
        get() = timetable is TimetableState.Ready && from != null && to != null && !sameStation && !isLoading

    /** IDFM timetables cover fewer days than SNCF ones. */
    val suburbanMissingFrom: LocalDate?
        get() = (timetable as? TimetableState.Ready)?.info?.suburbanUntil?.takeIf { date > it }
}

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TimetableRepository.get(application)
    private val nomade = NomadeApi(Http.client(application))
    private val realtime = GtfsRealtimeApi(Http.client(application))
    private val searchLock = Mutex()
    private var searchJob: Job? = null
    private var cachedAlerts: List<ServiceAlert>? = null
    private var alertsLoadedAt = 0L

    private val _state = MutableStateFlow(
        // Timetables are in Paris time, whatever the phone's time zone.
        LocalDateTime.now(PARIS).truncatedTo(ChronoUnit.MINUTES).let {
            UiState(date = it.toLocalDate(), time = it.toLocalTime())
        },
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        repository.start()
        viewModelScope.launch {
            repository.state.collect { timetable -> _state.update { it.copy(timetable = timetable) } }
        }
    }

    fun retryDownload() = repository.retry()
    fun setFromText(text: String) = _state.update { it.copy(fromText = text, from = null) }
    fun setToText(text: String) = _state.update { it.copy(toText = text, to = null) }
    fun selectFrom(station: Station) = _state.update { it.copy(fromText = station.name, from = station) }
    fun selectTo(station: Station) = _state.update { it.copy(toText = station.name, to = station) }
    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }
    fun setTime(time: LocalTime) = _state.update { it.copy(time = time) }
    fun swapStations() = _state.update {
        it.copy(fromText = it.toText, toText = it.fromText, from = it.to, to = it.from)
    }
    fun track(trip: Trip?) = _state.update { it.copy(trackedTrip = trip) }
    fun showSources(show: Boolean) = _state.update { it.copy(showSources = show) }

    fun search() {
        val s = _state.value
        load(s.date, s.time.toSecondOfDay(), replace = true)
    }

    fun loadNextDay() {
        val next = _state.value.nextDay ?: return
        load(next, 0, replace = false)
    }

    private fun load(date: LocalDate, startSeconds: Int, replace: Boolean) {
        val s = _state.value
        val ready = s.timetable as? TimetableState.Ready ?: return
        val from = s.from ?: return
        val to = s.to ?: return
        val planner = ready.planner
        val table = planner.table
        searchJob?.cancel()
        _state.update {
            it.copy(
                isLoading = true,
                hasSearched = true,
                error = null,
                itineraries = if (replace) emptyList() else it.itineraries,
                nextDay = if (replace) null else it.nextDay,
                realtimeUnavailable = if (replace) false else it.realtimeUnavailable,
                alerts = if (replace) emptyMap() else it.alerts,
                alertsFailed = if (replace) emptySet() else it.alertsFailed,
            )
        }
        searchJob = viewModelScope.launch {
            if (date < table.firstDate || date > table.lastDate) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Les horaires ne sont disponibles que du ${table.firstDate.format(SHORT_DATE)} au ${table.lastDate.format(SHORT_DATE)}.",
                    )
                }
                return@launch
            }
            val fromIndex = planner.resolve(from)
            val toIndex = planner.resolve(to)
            if (fromIndex == null || toIndex == null) {
                _state.update { it.copy(isLoading = false, error = "Cette gare n'est plus dans les horaires. Choisis-la de nouveau.") }
                return@launch
            }
            val found = search(planner, fromIndex, toIndex, date, startSeconds)
            _state.update {
                it.copy(
                    isLoading = false,
                    itineraries = it.itineraries + found,
                    nextDay = date.plusDays(1).takeIf { next -> next <= table.lastDate },
                )
            }
            applyRealtime()
        }
    }

    private suspend fun search(planner: JourneyPlanner, from: Int, to: Int, date: LocalDate, start: Int) =
        withContext(Dispatchers.Default) {
            searchLock.withLock { planner.search(from, to, date, start) }
        }

    /** Planned times show first; slower real-time data then completes them. */
    private suspend fun applyRealtime() {
        val itineraries = _state.value.itineraries
        val now = LocalDateTime.now(PARIS)
        val soon = itineraries.flatMap { it.rides }.filter { ride ->
            ride.trip.departure < now.plus(REALTIME_HORIZON) && ride.trip.arrival > now.minus(REALTIME_HORIZON)
        }
        if (soon.isEmpty()) return

        var unavailable = false
        val feed: LiveFeed? = try {
            nomade.liveFeed()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NomadeError) {
            unavailable = true
            null
        }
        val missing = soon.any { feed?.find(it.trip) == null }
        val updates: List<RealtimeTrip> = if (!missing) {
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
        _state.update {
            it.copy(
                itineraries = RealtimeOverlay.apply(it.itineraries, feed, updates),
                realtimeUnavailable = unavailable,
            )
        }
    }

    /** When a journey is expanded. The feed weighs about 1 MB, so it is only reloaded after a few minutes. */
    fun loadAlerts(itinerary: Itinerary) {
        val s = _state.value
        if (itinerary.id in s.alerts) return
        viewModelScope.launch {
            try {
                val all = cachedAlerts?.takeIf { System.currentTimeMillis() - alertsLoadedAt < ALERTS_CACHE_MILLIS }
                    ?: realtime.alerts().also {
                        cachedAlerts = it
                        alertsLoadedAt = System.currentTimeMillis()
                    }
                val matching = alertsFor(itinerary, all)
                _state.update {
                    it.copy(alerts = it.alerts + (itinerary.id to matching), alertsFailed = it.alertsFailed - itinerary.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NomadeError) {
                _state.update { it.copy(alertsFailed = it.alertsFailed + itinerary.id) }
            }
        }
    }

    private fun alertsFor(itinerary: Itinerary, alerts: List<ServiceAlert>): List<ServiceAlert> =
        alerts.filter { alert ->
            itinerary.rides.any { ride ->
                val trip = ride.trip
                val concerned = alert.concerns(trip.number.split('-'), trip.fromKey, trip.toKey)
                concerned && alert.isActiveAt(trip.departure.atZone(PARIS).toEpochSecond())
            }
        }.distinctBy { it.title to it.description }

    private companion object {
        val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)
        val REALTIME_HORIZON: Duration = Duration.ofHours(6)
        const val ALERTS_CACHE_MILLIS = 5 * 60 * 1000L
    }
}
