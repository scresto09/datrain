package com.scresto.datrain.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scresto.datrain.data.AlertsCache
import com.scresto.datrain.data.ServiceAlert
import com.scresto.datrain.data.CancelledQuery
import com.scresto.datrain.data.FavoriteTrain
import com.scresto.datrain.data.FavoriteWatchWorker
import com.scresto.datrain.data.GtfsRealtimeApi
import com.scresto.datrain.data.Http
import com.scresto.datrain.data.Itinerary
import com.scresto.datrain.data.JourneyPlanner
import com.scresto.datrain.data.NomadeApi
import com.scresto.datrain.data.NomadeError
import com.scresto.datrain.data.PARIS
import com.scresto.datrain.data.RealtimeOverlay
import com.scresto.datrain.data.RecentHistory
import com.scresto.datrain.data.RecentHistoryStore
import com.scresto.datrain.data.RecentJourney
import com.scresto.datrain.data.RecentStation
import com.scresto.datrain.data.RecentTrain
import com.scresto.datrain.data.RideLeg
import com.scresto.datrain.data.Station
import com.scresto.datrain.data.TimetableMerger
import com.scresto.datrain.data.TimetableRepository
import com.scresto.datrain.data.TimetableState
import com.scresto.datrain.data.Trip
import com.scresto.datrain.widget.DatrainWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TimetableRepository.get(application)
    private val nomade = NomadeApi(Http.client(application))
    private val realtime = GtfsRealtimeApi(Http.client(application))
    private val recentStore = RecentHistoryStore(application)
    private val searchLock = Mutex()
    private var searchJob: Job? = null
    private var lastQuery: CancelledQuery? = null
    private var trainSearchJob: Job? = null
    private var favoritesJob: Job? = null
    /** What the screen knew when it last started the background check: see [catchUpBackground]. */
    private var caughtUp: String? = null
    private val alertsCache = AlertsCache.get(application)
    private val favoritesLoader = FavoritesLoader(application, nomade, realtime, alertsCache)
    private val resultsRealtime = ResultsRealtime(nomade, realtime)

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
    fun setTypeTime(typing: Boolean) = updateRecent { it.copy(typeTime = typing) }
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
            val rides = favoritesLoader.runToOpen(favorite, planner, LocalDateTime.now(PARIS))
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
            val shown = _state.value.favoriteRuns
            val runs = withContext(Dispatchers.Default) { favoritesLoader.planned(favorites, planner, shown, now) }
            _state.update { it.copy(favoriteRuns = runs) }
            if (favorites.isEmpty()) return@launch
            var liveRead = false
            // Both run at once: each applies its part to what the other has already shown, rather than
            // replacing it with the list it started from.
            var alerts: List<ServiceAlert>? = null
            val live = launch {
                try {
                    val updated = favoritesLoader.live(_state.value.favoriteRuns, now)
                    _state.update { it.copy(favoriteRuns = alerts?.let { known -> favoritesLoader.withMessages(updated, known) } ?: updated) }
                    liveRead = true
                } catch (e: NomadeError) {
                    // The planned times remain shown.
                }
            }
            val messages = launch {
                try {
                    val known = favoritesLoader.alerts()
                    alerts = known
                    _state.update { it.copy(favoriteRuns = favoritesLoader.withMessages(it.favoriteRuns, known)) }
                } catch (e: NomadeError) {
                    // No count: the favorites stay usable without alerts.
                }
            }
            live.join()
            messages.join()
            if (liveRead) catchUpBackground(alerts != null)
        }
    }

    /**
     * The screen reads real time itself; the widget and the notifications only know what the background
     * check saved. When the screen knows more (a delay, a cancellation, a message), that check runs at once,
     * reading the full feed as the screen does, so that the widget shows the same and a notification goes out.
     * Once for each new thing seen: the feed may have changed in between, and the same difference must not
     * start a check every refresh.
     */
    private fun catchUpBackground(messagesKnown: Boolean) {
        val app = getApplication<Application>()
        val history = _state.value.recent
        val widget = DatrainWidget.isPlaced(app)
        val watched = { favorite: FavoriteTrain -> widget || (history.notifications && favorite.notify) }
        val runs = _state.value.favoriteRuns
        val known = favoritesLoader.liveSignature(runs, watched, messagesKnown)
        if (known == caughtUp || !favoritesLoader.aheadOfSaved(runs, watched, messagesKnown)) return
        caughtUp = known
        // Not tied to the favorites refresh: a new refresh must not cut it off.
        viewModelScope.launch { FavoriteWatchWorker.check(app, force = true, fullFeed = true) }
    }

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
            val query = CancelledQuery(from, to, date, startSeconds, arriveBy)
            lastQuery = query
            applyRealtime(query)
            loadResultsAlerts()
        }
    }

    private suspend fun search(planner: JourneyPlanner, from: IntArray, to: IntArray, date: LocalDate, seconds: Int, arriveBy: Boolean) =
        withContext(Dispatchers.Default) {
            searchLock.withLock {
                if (arriveBy) planner.searchArrivingBy(from, to, date, seconds) else planner.search(from, to, date, seconds)
            }
        }

    /** While results or favorites are on screen, their real time is read again now and then. */
    fun refreshShown() {
        val s = _state.value
        if (!s.hasSearched) {
            refreshFavorites()
            return
        }
        val query = lastQuery ?: return
        if (s.isLoading || s.itineraries.isEmpty()) return
        viewModelScope.launch { applyRealtime(query) }
    }

    private suspend fun applyRealtime(query: CancelledQuery) {
        val applied = resultsRealtime.apply(_state.value.itineraries, query, LocalDateTime.now(PARIS)) ?: return
        _state.update { it.copy(itineraries = applied.itineraries, realtimeUnavailable = applied.unavailable) }
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

    private companion object {
        val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)
        const val END_OF_DAY = 24 * 3600 - 1
        const val TYPING_PAUSE_MILLIS = 300L
    }
}
