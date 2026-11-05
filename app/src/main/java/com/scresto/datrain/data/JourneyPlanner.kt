package com.scresto.datrain.data

import android.util.Log
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Station and journey search in the loaded timetables. One search at a time: the caller ensures it. */
class JourneyPlanner(val table: RailTimetable) {
    private var cachedDate: LocalDate? = null
    private var cachedSearch: JourneySearch? = null
    private val searchNames: Array<String> by lazy { Array(table.stationCount) { foldName(table.stationNames[it]) } }

    fun searchStations(query: String, limit: Int = 8): List<Station> {
        val words = foldName(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return (0 until table.stationCount)
            .filter { i -> words.all { it in searchNames[i] } }
            .sortedWith(compareBy({ !searchNames[it].startsWith(words.first()) }, { table.stationNames[it].length }, { table.stationNames[it] }))
            .take(limit)
            .map(::station)
    }

    private fun station(i: Int) = Station(table.stationNames[i], table.stationUic[i], i)

    /** Finds a station chosen before a timetable update. */
    fun resolve(station: Station): Int? {
        val index = station.index
        if (index < table.stationCount && table.stationNames[index] == station.name) return index
        if (station.uic != 0) table.stationUic.indexOf(station.uic).takeIf { it >= 0 }?.let { return it }
        return table.stationNames.indexOf(station.name).takeIf { it >= 0 }
    }

    fun search(from: Int, to: Int, date: LocalDate, startSeconds: Int): List<Itinerary> {
        val start = System.nanoTime()
        val search = if (cachedDate == date) cachedSearch!! else JourneySearch(table, date).also {
            cachedDate = date
            cachedSearch = it
        }
        val found = search.search(from, to, startSeconds).map { itinerary(it, date) }
        Log.i("Datrain", "Search in ${(System.nanoTime() - start) / 1_000_000} ms: ${found.size} journeys")
        return found
    }

    private fun itinerary(journey: Journey, date: LocalDate): Itinerary =
        Itinerary(
            journey.legs.map { leg ->
                if (leg.trip < 0) transfer(leg) else ride(leg, date)
            },
        )

    private fun transfer(leg: JourneyLeg): TransferLeg {
        val walks = table.transfers[leg.fromStation]
        var seconds = 0
        for (i in walks.indices step 2) if (walks[i] == leg.toStation) seconds = walks[i + 1]
        return TransferLeg(
            fromName = table.stationNames[leg.fromStation],
            toName = table.stationNames[leg.toStation],
            seconds = seconds,
            isEstimate = seconds == TimetableMerger.PARIS_TRANSFER_SECONDS,
        )
    }

    private fun ride(leg: JourneyLeg, date: LocalDate): RideLeg {
        val trip = leg.trip
        val serviceDate = date.plusDays(leg.dayOffset.toLong())
        val midnight = serviceDate.atStartOfDay()
        val stations = table.patternStations[table.tripPattern[trip]]
        val first = table.tripOffset[trip]
        val stops = stations.mapIndexed { i, station ->
            LiveStop(
                uic = stationKey(station),
                arrivalSeconds = if (i == 0) null else table.times[first + i * 2],
                departureSeconds = if (i == stations.lastIndex) null else table.times[first + i * 2 + 1],
            )
        }
        val infos = stations.associate { station ->
            stationKey(station) to StationInfo(
                table.stationNames[station],
                LatLon(table.stationLat[station].toDouble(), table.stationLon[station].toDouble()),
            )
        }
        val departure = leg.departure - leg.dayOffset * JourneySearch.DAY
        val arrival = leg.arrival - leg.dayOffset * JourneySearch.DAY
        return RideLeg(
            trip = Trip(
                number = table.strings[table.tripNumber[trip]],
                product = table.strings[table.tripLabel[trip]],
                destination = table.stationNames[stations.last()],
                serviceDate = serviceDate,
                departure = midnight.plusSeconds(departure.toLong()),
                arrival = midnight.plusSeconds(arrival.toLong()),
                fromKey = stationKey(leg.fromStation),
                toKey = stationKey(leg.toStation),
                plannedStops = stops,
                plannedStations = infos,
            ),
            fromName = table.stationNames[leg.fromStation],
            toName = table.stationNames[leg.toStation],
            isBus = table.tripMode[trip] == RailTimetable.MODE_BUS,
        )
    }

    /** The UIC code links the station to live tracking; added IDFM stations have none. */
    private fun stationKey(station: Int): String =
        table.stationUic[station].takeIf { it != 0 }?.toString() ?: "station-$station"

    private companion object {
        fun foldName(text: String): String = TimetableMerger.foldAccents(text).replace('-', ' ').replace('\'', ' ')
    }
}

/** Applies known delays, platforms and cancellations to the journeys found. */
object RealtimeOverlay {
    private val START_DATE = DateTimeFormatter.BASIC_ISO_DATE

    fun apply(
        itineraries: List<Itinerary>,
        feed: LiveFeed?,
        updates: List<RealtimeTrip>,
        zone: ZoneId = PARIS,
    ): List<Itinerary> {
        if (feed == null && updates.isEmpty()) return itineraries
        val updatesByNumber = updates.groupBy { it.number }
        return itineraries.map { itinerary ->
            Itinerary(
                itinerary.legs.map { leg ->
                    if (leg is RideLeg) leg.copy(trip = withRealtime(leg.trip, feed, updatesByNumber, zone)) else leg
                },
            )
        }
    }

    private fun withRealtime(
        trip: Trip,
        feed: LiveFeed?,
        updates: Map<String, List<RealtimeTrip>>,
        zone: ZoneId,
    ): Trip {
        val live = feed?.takeIf { it.isFresh() }?.find(trip)
        if (live != null) {
            val from = live.stops.firstOrNull { it.uic == trip.fromKey }
            val to = live.stops.lastOrNull { it.uic == trip.toKey }
            return trip.copy(
                departureDelayMinutes = (from?.departureDelaySeconds ?: 0) / 60,
                arrivalDelayMinutes = (to?.arrivalDelaySeconds ?: 0) / 60,
                platform = from?.platform,
                cancelled = live.cancelled || from?.removed == true || to?.removed == true,
            )
        }
        val date = trip.serviceDate.format(START_DATE)
        val update = TimetableMerger.numbers(trip.number)
            .firstNotNullOfOrNull { number -> updates[number]?.firstOrNull { it.startDate == date } }
            ?: return trip
        val from = update.stops.firstOrNull { it.uic == trip.fromKey }
        val to = update.stops.lastOrNull { it.uic == trip.toKey }
        return trip.copy(
            departureDelayMinutes = delayMinutes(from?.departureEpoch, trip.departure, zone),
            arrivalDelayMinutes = delayMinutes(to?.arrivalEpoch, trip.arrival, zone),
            cancelled = update.cancelled,
        )
    }

    private fun LiveFeed.isFresh() = java.time.OffsetDateTime.now(PARIS) < staleAfter

    private fun delayMinutes(epoch: Long?, planned: LocalDateTime, zone: ZoneId): Int {
        if (epoch == null) return 0
        return ((epoch - planned.atZone(zone).toEpochSecond()) / 60).toInt().coerceAtLeast(0)
    }
}
