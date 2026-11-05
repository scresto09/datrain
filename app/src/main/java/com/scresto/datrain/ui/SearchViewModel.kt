package com.scresto.datrain.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scresto.datrain.data.AlertsCache
import com.scresto.datrain.data.CancelledQuery
import com.scresto.datrain.data.FavoriteTrain
import com.scresto.datrain.data.FavoriteWatch
import com.scresto.datrain.data.FavoriteWatchWorker
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
import com.scresto.datrain.data.RecentHistory
import com.scresto.datrain.data.RecentHistoryStore
import com.scresto.datrain.data.RecentJourney
import com.scresto.datrain.data.RecentStation
import com.scresto.datrain.data.RecentTrain
import com.scresto.datrain.data.RideLeg
import com.scresto.datrain.data.ServiceAlert
import com.scresto.datrain.data.Station
import com.scresto.datrain.data.TimetableMerger
import com.scresto.datrain.data.TimetableRepository
import com.scresto.datrain.data.TimetableState
import com.scresto.datrain.data.Trip
import com.scresto.datrain.widget.DatrainWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** [rides] is null if the known timetables no longer have the run. [isLive]: real-time data is known. */
data class FavoriteRun(
    val favorite: FavoriteTrain,
    /** One train, or each train of a journey with changes. */
    val rides: List<RideLeg>?,
    val isLive: Boolean = false,
    val messages: Int = 0,
    /** The next run in the timetables. */
    val next: List<RideLeg>? = rides,
    /** An earlier run whose planned arrival has passed: if it is late, it is still the one to show. */
    val late: List<RideLeg>? = null,
) {
    val ride: RideLeg? get() = rides?.firstOrNull()
    val itinerary: Itinerary? get() = rides?.let(::Itinerary)
}

data class TrainSearchState(
    val query: String = "",
    val date: LocalDate,
    val results: List<RideLeg> = emptyList(),
    val isLoading: Boolean = false,
)

data class UiState(
    val timetable: TimetableState = TimetableState.Loading,
    val fromText: String = "",
    val toText: String = "",
    val from: Station? = null,
    val to: Station? = null,
    val date: LocalDate,
    val time: LocalTime,
    val arriveBy: Boolean = false,
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val itineraries: List<Itinerary> = emptyList(),
    /** Next day to show: the following one, or the previous one when searching by arrival time. */
    val moreDay: LocalDate? = null,
    val realtimeUnavailable: Boolean = false,
    val error: String? = null,
    val trackedTrip: Trip? = null,
    /** All trains of the journey the tracked one belongs to, when it has changes. */
    val trackedJourney: List<Trip> = emptyList(),
    val showSources: Boolean = false,
    /** Alerts per journey, once its details are open. */
    val alerts: Map<String, List<ServiceAlert>> = emptyMap(),
    val alertsFailed: Set<String> = emptySet(),
    val recent: RecentHistory = RecentHistory(),
    val favoriteRuns: List<FavoriteRun> = emptyList(),
    val showTrainSearch: Boolean = false,
    val trainSearch: TrainSearchState = TrainSearchState(date = date),
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
    private val recentStore = RecentHistoryStore(application)
    private val searchLock = Mutex()
    private var searchJob: Job? = null
    private var trainSearchJob: Job? = null
    private var favoritesJob: Job? = null
    private val alertsCache = AlertsCache.get(application)

    private val _state = MutableStateFlow(
        // Timetables are in Paris time, whatever the phone's time zone.
        LocalDateTime.now(PARIS).truncatedTo(ChronoUnit.MINUTES).let {
            UiState(date = it.toLocalDate(), time = it.toLocalTime(), recent = recentStore.load().withoutFinishedTrains(it))
        },
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        repository.start()
        FavoriteWatchWorker.sync(application, _state.value.recent)
        viewModelScope.launch {
            repository.state.collect { timetable ->
                _state.update { it.copy(timetable = timetable) }
                refreshFavorites()
            }
        }
    }

    fun retryDownload() = repository.retry()
    fun setFromText(text: String) = _state.update { it.copy(fromText = text, from = null) }
    fun setToText(text: String) = _state.update { it.copy(toText = text, to = null) }
    fun selectFrom(station: Station) = _state.update { it.copy(fromText = station.name, from = station) }
    fun selectTo(station: Station) = _state.update { it.copy(toText = station.name, to = station) }
    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }
    fun setTime(time: LocalTime, arriveBy: Boolean) = _state.update { it.copy(time = time, arriveBy = arriveBy) }
    fun swapStations() = _state.update {
        it.copy(fromText = it.toText, toText = it.fromText, from = it.to, to = it.from)
    }
    fun track(trip: Trip?, journey: List<Trip> = emptyList()) =
        _state.update { it.copy(trackedTrip = trip, trackedJourney = if (trip == null) emptyList() else journey) }
    fun showSources(show: Boolean) = _state.update { it.copy(showSources = show) }

    fun selectRecentJourney(journey: RecentJourney) = _state.update {
        it.copy(
            fromText = journey.from.name,
            toText = journey.to.name,
            from = journey.from.toStation(),
            to = journey.to.toStation(),
        )
    }

    fun toggleFavorite(train: FavoriteTrain) {
        if (_state.value.recent.isFavorite(train)) removeFavorite(train) else addFavorite(train)
    }

    fun addFavorite(train: FavoriteTrain) {
        updateRecent { it.addingFavorite(train) }
        syncWatch()
        refreshFavorites()
    }

    fun removeFavorite(train: FavoriteTrain) {
        updateRecent { it.withoutFavorite(train) }
        syncWatch()
        refreshFavorites()
    }

    fun setNotifications(enabled: Boolean) {
        updateRecent { it.copy(notifications = enabled) }
        syncWatch()
    }

    fun setFavoriteNotify(train: FavoriteTrain, notify: Boolean) {
        updateRecent { it.withFavoriteNotify(train, notify) }
        syncWatch()
        refreshFavorites()
    }

    fun sendTestNotification() {
        val favorites = _state.value.recent.favorites
        viewModelScope.launch(Dispatchers.Default) { FavoriteWatchWorker.sendTest(getApplication(), favorites) }
    }

    /** The background check and the widget follow the favorites. */
    private fun syncWatch() {
        FavoriteWatchWorker.sync(getApplication(), _state.value.recent)
        viewModelScope.launch { DatrainWidget.refresh(getApplication()) }
    }

    /** From a notification: opens the train's map at its next run. */
    fun openFavorite(key: String) {
        val planner = (_state.value.timetable as? TimetableState.Ready)?.planner ?: return
        val favorite = _state.value.recent.favorites.firstOrNull { it.key == key } ?: return
        viewModelScope.launch {
            val now = LocalDateTime.now(PARIS)
            val (next, late) = withContext(Dispatchers.Default) {
                planner.nextJourney(favorite, now) to planner.nextJourney(favorite, now.minus(LATE_RUN_WINDOW))
            }
            // A late run not there yet is the one the notification was about.
            val rides = if (late != null && ids(late) != ids(next)) {
                try {
                    liveRun(FavoriteRun(favorite, next, next = next, late = late), nomade.liveFeed(), now).rides
                } catch (e: NomadeError) {
                    next
                }
            } else {
                next
            }
            if (rides != null) openRun(rides)
        }
    }

    /** A journey opens on its first train not yet arrived, with the others on the map. */
    fun openRun(rides: List<RideLeg>) {
        val now = LocalDateTime.now(PARIS)
        val current = rides.firstOrNull { it.trip.estimatedArrival > now } ?: rides.last()
        trackRide(current, rides.map { it.trip })
    }

    /** Next runs from the timetables first, then real time when one is soon. */
    fun refreshFavorites() {
        favoritesJob?.cancel()
        val planner = (_state.value.timetable as? TimetableState.Ready)?.planner ?: return
        val favorites = _state.value.recent.favorites
        favoritesJob = viewModelScope.launch {
            val now = LocalDateTime.now(PARIS)
            val previous = _state.value.favoriteRuns
            val runs = withContext(Dispatchers.Default) {
                favorites.map { favorite ->
                    val next = planner.nextJourney(favorite, now)
                    val late = planner.nextJourney(favorite, now.minus(LATE_RUN_WINDOW))?.takeIf { ids(it) != ids(next) }
                    val planned = FavoriteRun(favorite, next, next = next, late = late)
                    // Same run as on screen: its delays and messages stay until fresh ones arrive, so nothing jumps.
                    previous.firstOrNull { shown ->
                        shown.favorite.key == favorite.key && (
                            runId(shown) == runId(planned) ||
                                (late != null && runId(shown) == ids(late) && shown.rides!!.last().trip.estimatedArrival > now)
                            )
                    }?.copy(favorite = favorite, next = next, late = late) ?: planned
                }.sortedForDisplay()
            }
            _state.update { it.copy(favoriteRuns = runs) }
            // A run taken out of the timetables because it is cancelled can only be seen in the live feed.
            if (favorites.isEmpty()) return@launch
            launch {
                try {
                    val feed = nomade.liveFeed()
                    _state.update { state ->
                        state.copy(
                            favoriteRuns = state.favoriteRuns.map { run -> liveRun(run, feed, now) }.sortedForDisplay(),
                        )
                    }
                } catch (e: NomadeError) {
                    // The planned times remain shown.
                }
            }
            launch {
                try {
                    val alerts = alertsCache.get()
                    _state.update { state ->
                        state.copy(
                            favoriteRuns = state.favoriteRuns.map { run ->
                                run.copy(
                                    messages = run.itinerary?.let { alertsFor(it, alerts).size } ?: 0,
                                )
                            },
                        )
                    }
                } catch (e: NomadeError) {
                    // No icon: the favorites stay usable without alerts.
                }
            }
        }
    }

    private fun liveRun(run: FavoriteRun, feed: LiveFeed, now: LocalDateTime): FavoriteRun {
        // Planned arrival passed, but the train is late and not there yet: it stays the one shown.
        run.late?.let { late ->
            val updated = RealtimeOverlay.apply(listOf(Itinerary(late)), feed, emptyList()).single().rides
            val tracked = late.any { feed.find(it.trip) != null }
            if (tracked && !Itinerary(updated).cancelled && updated.last().trip.estimatedArrival > now) {
                return run.copy(rides = updated, isLive = true)
            }
        }
        if (!run.favorite.isJourney) {
            val cancelled = FavoriteWatch.missingRun(run.favorite, run.next?.firstOrNull(), feed, now)
            if (cancelled != null) return run.copy(rides = listOf(cancelled), isLive = true)
        }
        val rides = run.next ?: return run.copy(rides = null)
        val live = rides.any { feed.find(it.trip) != null }
        val updated = RealtimeOverlay.apply(listOf(Itinerary(rides)), feed, emptyList()).single().rides
        return run.copy(rides = updated, isLive = live)
    }

    private fun runId(run: FavoriteRun) = ids(run.rides)

    private fun ids(rides: List<RideLeg>?) = rides?.map { "${it.trip.number}@${it.trip.departure}" }

    private fun List<FavoriteRun>.sortedForDisplay() =
        sortedWith(compareBy({ it.ride == null }, { it.ride?.trip?.departure }))

    fun showTrainSearch(show: Boolean) = _state.update {
        it.copy(showTrainSearch = show, trainSearch = if (show) it.trainSearch else it.trainSearch.copy(query = "", results = emptyList()))
    }

    fun setTrainQuery(text: String) {
        _state.update { it.copy(trainSearch = it.trainSearch.copy(query = text)) }
        searchTrains()
    }

    fun setTrainSearchDate(date: LocalDate) {
        _state.update { it.copy(trainSearch = it.trainSearch.copy(date = date)) }
        searchTrains()
    }

    private fun searchTrains() {
        trainSearchJob?.cancel()
        val search = _state.value.trainSearch
        val planner = (_state.value.timetable as? TimetableState.Ready)?.planner
        if (search.query.isBlank() || planner == null) {
            _state.update { it.copy(trainSearch = it.trainSearch.copy(results = emptyList(), isLoading = false)) }
            return
        }
        _state.update { it.copy(trainSearch = it.trainSearch.copy(isLoading = true)) }
        trainSearchJob = viewModelScope.launch {
            delay(TYPING_PAUSE_MILLIS)
            val found = withContext(Dispatchers.Default) { planner.searchTrainNumber(search.query, search.date) }
            _state.update { it.copy(trainSearch = it.trainSearch.copy(results = found)) }
            // The live feed only covers today; the timetables remain enough without it.
            val updated = if (search.date == LocalDate.now(PARIS)) {
                try {
                    RealtimeOverlay.applyToNumberSearch(found, nomade.liveFeed(), search.query, search.date)
                } catch (e: NomadeError) {
                    found
                }
            } else {
                found
            }
            _state.update { it.copy(trainSearch = it.trainSearch.copy(results = updated, isLoading = false)) }
        }
    }

    /** A result was chosen: its number joins the history, whole, even if only the start was typed. */
    fun openSearchedTrain(ride: RideLeg) {
        val typed = _state.value.trainSearch.query.trim().uppercase()
        val numbers = TimetableMerger.numbers(ride.trip.number)
        val number = numbers.firstOrNull { it.uppercase().startsWith(typed) } ?: numbers.first()
        updateRecent { it.addingNumber(number) }
        trackRide(ride)
    }

    /** The keyboard's search key: only a complete number is worth keeping. */
    fun saveTrainQuery() {
        val search = _state.value.trainSearch
        val typed = search.query.trim().uppercase()
        val exact = search.results.any { ride -> TimetableMerger.numbers(ride.trip.number).any { it.uppercase() == typed } }
        if (exact) updateRecent { it.addingNumber(typed) }
    }

    fun forgetNumber(number: String) = updateRecent { it.withoutNumber(number) }

    fun trackRide(ride: RideLeg, journey: List<Trip> = emptyList()) {
        updateRecent { it.addingTrain(RecentTrain.of(ride)) }
        track(ride.trip, journey.takeIf { it.size > 1 }.orEmpty())
    }

    fun reopenTrain(train: RecentTrain) {
        updateRecent { it.addingTrain(train) }
        track(train.toTrip())
    }

    fun forgetTrain(train: RecentTrain) = updateRecent { it.without(train) }

    fun forgetStation(station: RecentStation) = updateRecent { it.without(station) }

    fun forgetJourney(journey: RecentJourney) = updateRecent { it.without(journey) }

    private fun updateRecent(change: (RecentHistory) -> RecentHistory) {
        val updated = change(_state.value.recent)
        _state.update { it.copy(recent = updated) }
        recentStore.save(updated)
    }

    /** Back to the favorites. The form keeps its stations and date. */
    fun clearResults() {
        searchJob?.cancel()
        _state.update {
            it.copy(
                isLoading = false,
                hasSearched = false,
                itineraries = emptyList(),
                moreDay = null,
                error = null,
                realtimeUnavailable = false,
            )
        }
        refreshFavorites()
    }

    fun search() {
        val s = _state.value
        if (s.canSearch) updateRecent { it.adding(s.from!!, s.to!!) }
        load(s.date, s.time.toSecondOfDay(), replace = true)
    }

    fun loadMoreDay() {
        val day = _state.value.moreDay ?: return
        load(day, if (_state.value.arriveBy) END_OF_DAY else 0, replace = false)
    }

    private fun load(date: LocalDate, startSeconds: Int, replace: Boolean) {
        val s = _state.value
        val ready = s.timetable as? TimetableState.Ready ?: return
        val from = s.from ?: return
        val to = s.to ?: return
        val planner = ready.planner
        val arriveBy = s.arriveBy
        val table = planner.table
        searchJob?.cancel()
        _state.update {
            it.copy(
                isLoading = true,
                hasSearched = true,
                error = null,
                itineraries = if (replace) emptyList() else it.itineraries,
                moreDay = if (replace) null else it.moreDay,
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
            val fromIndex = planner.resolveAll(from)
            val toIndex = planner.resolveAll(to)
            if (fromIndex == null || toIndex == null) {
                _state.update { it.copy(isLoading = false, error = "Cette gare n'est plus dans les horaires. Choisissez-la de nouveau.") }
                return@launch
            }
            val found = search(planner, fromIndex, toIndex, date, startSeconds, arriveBy)
            _state.update {
                it.copy(
                    isLoading = false,
                    itineraries = (it.itineraries + found).distinctBy { itinerary -> itinerary.id },
                    moreDay = if (arriveBy) {
                        date.minusDays(1).takeIf { day -> day >= table.firstDate }
                    } else {
                        date.plusDays(1).takeIf { day -> day <= table.lastDate }
                    },
                )
            }
            applyRealtime(CancelledQuery(from, to, date, startSeconds, arriveBy))
            loadResultsAlerts()
        }
    }

    private suspend fun search(planner: JourneyPlanner, from: IntArray, to: IntArray, date: LocalDate, seconds: Int, arriveBy: Boolean) =
        withContext(Dispatchers.Default) {
            searchLock.withLock {
                if (arriveBy) planner.searchArrivingBy(from, to, date, seconds) else planner.search(from, to, date, seconds)
            }
        }

    /** Planned times show first; slower real-time data then completes them. */
    private suspend fun applyRealtime(query: CancelledQuery) {
        val itineraries = _state.value.itineraries
        val now = LocalDateTime.now(PARIS)
        val soon = itineraries.flatMap { it.rides }.filter { ride ->
            ride.trip.departure < now.plus(REALTIME_HORIZON) && ride.trip.arrival > now.minus(REALTIME_HORIZON)
        }
        // Today, the live feed may also hold cancelled trains that the search could not find.
        if (soon.isEmpty() && query.date != now.toLocalDate()) return

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
            val updated = RealtimeOverlay.apply(it.itineraries, feed, updates)
            val extra = feed?.let { live ->
                RealtimeOverlay.cancelledMissing(live, query, updated) + RealtimeOverlay.lateDepartures(live, query, updated)
            }.orEmpty()
            val all = (updated + extra).distinctBy { itinerary -> itinerary.id }
            it.copy(
                itineraries = if (query.arriveBy) {
                    all.sortedByDescending { itinerary -> itinerary.arrival }
                } else {
                    all.sortedBy { itinerary -> itinerary.departure }
                },
                realtimeUnavailable = unavailable,
            )
        }
    }

    /** So that each result can show it has alerts. On failure, opening a journey tries again. */
    private suspend fun loadResultsAlerts() {
        try {
            val all = alertsCache.get()
            _state.update {
                it.copy(alerts = it.itineraries.associate { itinerary -> itinerary.id to alertsFor(itinerary, all) })
            }
        } catch (e: NomadeError) {
            // Nothing to show: the results stay usable without alerts.
        }
    }

    /** When a journey is expanded. */
    fun loadAlerts(itinerary: Itinerary) {
        val s = _state.value
        if (itinerary.id in s.alerts) return
        viewModelScope.launch {
            try {
                val matching = alertsFor(itinerary, alertsCache.get())
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
        /** How late a favorite's run can be and still be shown instead of the next one. */
        val LATE_RUN_WINDOW: Duration = Duration.ofHours(6)
        const val END_OF_DAY = 24 * 3600 - 1
        const val TYPING_PAUSE_MILLIS = 300L
    }
}
