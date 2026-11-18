package com.scresto.datrain.ui

import com.scresto.datrain.data.Alternatives
import com.scresto.datrain.data.FavoriteTrain
import com.scresto.datrain.data.Itinerary
import com.scresto.datrain.data.RecentHistory
import com.scresto.datrain.data.RideLeg
import com.scresto.datrain.data.ServiceAlert
import com.scresto.datrain.data.Station
import com.scresto.datrain.data.TimetableState
import com.scresto.datrain.data.Trip
import java.time.LocalDate
import java.time.LocalTime

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
    /** In place of a cancelled run: the train before, while it can be caught, and the one after. */
    val alternatives: Alternatives? = null,
    /** In place of a cancelled run, the temporary follow taken: "le 17:09". */
    val replacedBy: String? = null,
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
    /** Opened in place of a cancelled run: its star offers a temporary follow first. */
    val trackedAsAlternative: Boolean = false,
    /** Start of the hours searched on the day asked: earlier trains are looked for before it. */
    val searchedFrom: LocalTime? = null,
    /** The step of the guided tour on screen, if any. */
    val tour: Int? = null,
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

    /** Earlier trains can be added above the results, down to the start of the day searched. */
    val canLoadEarlier: Boolean
        get() = hasSearched && !arriveBy && !isLoading && searchedFrom != null && searchedFrom > LocalTime.MIDNIGHT
    val canSearch: Boolean
        get() = timetable is TimetableState.Ready && from != null && to != null && !sameStation && !isLoading

    /** IDFM timetables cover fewer days than SNCF ones. */
    val suburbanMissingFrom: LocalDate?
        get() = (timetable as? TimetableState.Ready)?.info?.suburbanUntil?.takeIf { date > it }
}
