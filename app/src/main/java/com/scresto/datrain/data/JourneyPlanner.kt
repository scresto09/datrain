package com.scresto.datrain.data

import android.util.Log
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Station and journey search in the loaded timetables. One search at a time: the caller ensures it. */
class JourneyPlanner(val table: RailTimetable) {
    private val searches = object : LinkedHashMap<LocalDate, JourneySearch>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, JourneySearch>) = size > 2
    }
    private val searchNames: Array<String> by lazy { Array(table.stationCount) { foldName(table.stationNames[it]) } }

    fun searchStations(query: String, limit: Int = 8): List<Station> {
        val words = foldName(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val groups = StationGroups.ALL
            .filter { group -> words.all { it in foldName(group.name) } && members(group).size > 1 }
            .map { Station(it.name, 0, -1) }
        val stations = (0 until table.stationCount)
            .filter { i -> words.all { it in searchNames[i] } }
            .sortedWith(compareBy({ !searchNames[it].startsWith(words.first()) }, { table.stationNames[it].length }, { table.stationNames[it] }))
            .map(::station)
        return (groups + stations).take(limit)
    }

    private fun members(group: StationGroups.Group): IntArray =
        (0 until table.stationCount).filter { table.stationUic[it] in group.uics }.toIntArray()

    /** All the stations a choice stands for: a whole city, or one station. */
    fun resolveAll(station: Station): IntArray? {
        StationGroups.named(station.name)?.let { group -> return members(group).takeIf { it.isNotEmpty() } }
        return resolve(station)?.let { intArrayOf(it) }
    }

    private fun station(i: Int) = Station(table.stationNames[i], table.stationUic[i], i)

    /** Finds a station chosen before a timetable update. */
    fun resolve(station: Station): Int? {
        val index = station.index
        if (index in 0 until table.stationCount && table.stationNames[index] == station.name) return index
        if (station.uic != 0) table.stationUic.indexOf(station.uic).takeIf { it >= 0 }?.let { return it }
        return table.stationNames.indexOf(station.name).takeIf { it >= 0 }
    }

    fun search(from: Int, to: Int, date: LocalDate, startSeconds: Int): List<Itinerary> =
        search(intArrayOf(from), intArrayOf(to), date, startSeconds)

    /** From any of [from] to any of [to]. */
    fun search(from: IntArray, to: IntArray, date: LocalDate, startSeconds: Int): List<Itinerary> {
        val start = System.nanoTime()
        val found = searchFor(date).search(from, to, startSeconds).map { itinerary(it, date) }
        Log.i("Datrain", "Search in ${(System.nanoTime() - start) / 1_000_000} ms: ${found.size} journeys")
        return found
    }

    /** Journeys arriving by [latestSeconds] on [date], closest to that time first. */
    fun searchArrivingBy(from: Int, to: Int, date: LocalDate, latestSeconds: Int): List<Itinerary> =
        searchArrivingBy(intArrayOf(from), intArrayOf(to), date, latestSeconds)

    fun searchArrivingBy(from: IntArray, to: IntArray, date: LocalDate, latestSeconds: Int): List<Itinerary> {
        val search = searchFor(date)
        var journeys = emptyList<Journey>()
        for (window in ARRIVE_BY_WINDOWS) {
            val start = (latestSeconds - window).coerceAtLeast(0)
            journeys = search.search(from, to, start, latestSeconds, latestSeconds, ARRIVE_BY_SCANS)
            if (journeys.size >= ARRIVE_BY_ENOUGH || start == 0) break
        }
        var found = journeys.map { itinerary(it, date) }
        if (journeys.size < ARRIVE_BY_ENOUGH) {
            // Not enough on that day: a night train leaving the evening before may arrive that morning.
            val previous = date.minusDays(1)
            found = found + searchFor(previous).search(
                from, to, JourneySearch.DAY / 2, JourneySearch.DAY, JourneySearch.DAY + latestSeconds, ARRIVE_BY_SCANS,
            ).filter { it.arrival >= JourneySearch.DAY }.map { itinerary(it, previous) }
        }
        return found.distinctBy { it.id }
            .sortedWith(compareByDescending<Itinerary> { it.arrival }.thenByDescending { it.departure })
            .take(ARRIVE_BY_LIMIT)
    }

    /** Trains running that day whose number starts with [query], the exact number first. */
    fun searchTrainNumber(query: String, date: LocalDate, limit: Int = NUMBER_RESULTS): List<RideLeg> {
        val wanted = query.trim().uppercase()
        if (wanted.isEmpty()) return emptyList()
        val dayIndex = ChronoUnit.DAYS.between(table.firstDate, date).toInt()
        val found = (0 until table.tripCount).filter { trip ->
            table.runsOn(trip, dayIndex) &&
                TimetableMerger.numbers(table.strings[table.tripNumber[trip]]).any { it.uppercase().startsWith(wanted) }
        }
        return found.map { trip ->
            val stations = table.patternStations[table.tripPattern[trip]]
            val first = table.tripOffset[trip]
            val whole = JourneyLeg(trip, stations.first(), stations.last(), table.times[first + 1], table.times[first + stations.size * 2 - 2])
            // No boarding or alighting station here: the map must not talk about the traveller's stop.
            ride(whole, date).let { it.copy(trip = it.trip.copy(fromKey = "", toKey = "")) }
        }
            .sortedWith(compareBy({ !TimetableMerger.numbers(it.trip.number).any { n -> n.equals(wanted, ignoreCase = true) } }, { it.trip.departure }))
            .take(limit)
    }

    /** The favorite's next run not yet finished at [now]; null if the known timetables no longer have one. */
    /**
     * The journey's next run: each train boards after the previous one arrives, within [MAX_CHANGE_HOURS].
     * A journey already under way stays until its last train arrives.
     */
    fun nextJourney(favorite: FavoriteTrain, now: LocalDateTime): List<RideLeg>? {
        if (!favorite.isJourney) return nextRun(favorite, now)?.let(::listOf)
        val legs = favorite.legs
        var from = now.minusHours(JOURNEY_LOOKBACK_HOURS)
        repeat(JOURNEY_ATTEMPTS) {
            val first = nextRun(legs.first(), from) ?: return null
            val rides = arrayListOf(first)
            for (leg in legs.drop(1)) {
                val previous = rides.last().trip
                val next = nextRun(leg, previous.arrival, boardingAfter = previous.arrival) ?: return null
                if (next.trip.departure > previous.arrival.plusHours(MAX_CHANGE_HOURS)) break
                rides += next
            }
            if (rides.size == legs.size && rides.last().trip.arrival > now) return rides
            from = first.trip.arrival
        }
        return null
    }

    fun nextRun(favorite: FavoriteTrain, now: LocalDateTime, boardingAfter: LocalDateTime? = null): RideLeg? {
        val from = table.stationNames.indexOf(favorite.fromName)
        val to = table.stationNames.indexOf(favorite.toName)
        if (from < 0 || to < 0) return null
        val numbers = TimetableMerger.numbers(favorite.number)
        val candidates = (0 until table.tripCount).mapNotNull { trip ->
            if (TimetableMerger.numbers(table.strings[table.tripNumber[trip]]).none { it in numbers }) return@mapNotNull null
            val stations = table.patternStations[table.tripPattern[trip]]
            val boarding = stations.indexOf(from)
            val alighting = stations.lastIndexOf(to)
            if (boarding < 0 || alighting <= boarding) null else Triple(trip, boarding, alighting)
        }
        if (candidates.isEmpty()) return null
        var date = maxOf(table.firstDate, now.toLocalDate().minusDays(1))
        while (date <= table.lastDate) {
            val dayIndex = ChronoUnit.DAYS.between(table.firstDate, date).toInt()
            val midnight = date.atStartOfDay()
            val best = candidates
                .filter { (trip, _, _) -> table.runsOn(trip, dayIndex) }
                .filter { (trip, _, alighting) ->
                    midnight.plusSeconds(table.times[table.tripOffset[trip] + alighting * 2].toLong()) > now
                }
                .filter { (trip, boarding, _) ->
                    boardingAfter == null || midnight.plusSeconds(table.times[table.tripOffset[trip] + boarding * 2 + 1].toLong()) >= boardingAfter
                }
                .minByOrNull { (trip, boarding, _) -> table.times[table.tripOffset[trip] + boarding * 2 + 1] }
            if (best != null) {
                val (trip, boarding, alighting) = best
                val first = table.tripOffset[trip]
                return ride(JourneyLeg(trip, from, to, table.times[first + boarding * 2 + 1], table.times[first + alighting * 2]), date)
            }
            date = date.plusDays(1)
        }
        return null
    }

    private fun searchFor(date: LocalDate): JourneySearch = searches.getOrPut(date) { JourneySearch(table, date) }

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
        /** A change longer than this is not the same journey. */
        const val MAX_CHANGE_HOURS = 6L
        const val JOURNEY_LOOKBACK_HOURS = 24L
        const val JOURNEY_ATTEMPTS = 40
        val ARRIVE_BY_WINDOWS = intArrayOf(4 * 3600, 8 * 3600, 16 * 3600, 24 * 3600)
        const val ARRIVE_BY_SCANS = 150
        const val ARRIVE_BY_ENOUGH = 3
        const val ARRIVE_BY_LIMIT = 12
        const val NUMBER_RESULTS = 20

        fun foldName(text: String): String = TimetableMerger.foldAccents(text).replace('-', ' ').replace('\'', ' ')
    }
}

/** One search: [seconds] is the earliest departure, or the latest arrival with [arriveBy]. */
data class CancelledQuery(val from: Station, val to: Station, val date: LocalDate, val seconds: Int, val arriveBy: Boolean)

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
                serviceNotes = live.serviceNotes(),
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

    /**
     * Trains known to be cancelled in advance are taken out of the published timetables:
     * they would vanish from the results without a word. They are put back from the live feed,
     * for direct journeys and the hours it covers.
     */
    fun cancelledMissing(feed: LiveFeed, query: CancelledQuery, existing: List<Itinerary>): List<Itinerary> {
        if (!feed.isFresh()) return emptyList()
        val shown = existing.flatMap { it.rides }.flatMap { ride ->
            TimetableMerger.numbers(ride.trip.number).map { it to ride.trip.serviceDate }
        }.toSet()
        return feed.trains
            .filter { it.cancelled && it.serviceDate == query.date && (it.number to it.serviceDate) !in shown }
            .mapNotNull { train -> cancelledJourney(train, query) }
    }

    /** Trains planned before the requested time, but so late that they can still be caught. */
    fun lateDepartures(feed: LiveFeed, query: CancelledQuery, existing: List<Itinerary>): List<Itinerary> {
        if (query.arriveBy || !feed.isFresh()) return emptyList()
        val shown = existing.flatMap { it.rides }.flatMap { ride ->
            TimetableMerger.numbers(ride.trip.number).map { it to ride.trip.serviceDate }
        }.toSet()
        return feed.trains
            .filter { !it.cancelled && it.hasRealtime && it.serviceDate == query.date && (it.number to it.serviceDate) !in shown }
            .mapNotNull { train -> lateJourney(train, query) }
    }

    private fun lateJourney(train: LiveTrain, query: CancelledQuery): Itinerary? {
        val fromIndex = train.stops.indexOfFirst { it.uic == query.from.uic.toString() && it.departureSeconds != null && !it.removed }
        if (fromIndex < 0) return null
        val toIndex = train.stops.indexOfLast { it.uic == query.to.uic.toString() && it.arrivalSeconds != null && !it.removed }
        if (toIndex <= fromIndex) return null
        val from = train.stops[fromIndex]
        val to = train.stops[toIndex]
        val departure = from.departureSeconds!!
        if (departure >= query.seconds || from.estimatedDeparture!! < query.seconds) return null
        val midnight = train.serviceDate.atStartOfDay()
        return Itinerary(
            listOf(
                RideLeg(
                    trip = Trip(
                        number = train.number,
                        product = productName(train.product),
                        destination = train.destination ?: query.to.name,
                        serviceDate = train.serviceDate,
                        departure = midnight.plusSeconds(departure.toLong()),
                        arrival = midnight.plusSeconds(to.arrivalSeconds!!.toLong()),
                        departureDelayMinutes = from.departureDelaySeconds / 60,
                        arrivalDelayMinutes = to.arrivalDelaySeconds / 60,
                        platform = from.platform,
                        fromKey = from.uic,
                        toKey = to.uic,
                        plannedStops = train.stops,
                        plannedStations = mapOf(
                            from.uic to StationInfo(query.from.name, null),
                            to.uic to StationInfo(query.to.name, null),
                        ),
                    ),
                    fromName = query.from.name,
                    toName = query.to.name,
                    isBus = false,
                ),
            ),
        )
    }

    private fun cancelledJourney(train: LiveTrain, query: CancelledQuery): Itinerary? {
        val fromIndex = train.stops.indexOfFirst { it.uic == query.from.uic.toString() && it.departureSeconds != null }
        if (fromIndex < 0) return null
        val toIndex = train.stops.indexOfLast { it.uic == query.to.uic.toString() && it.arrivalSeconds != null }
        if (toIndex <= fromIndex) return null
        val departure = train.stops[fromIndex].departureSeconds!!
        val arrival = train.stops[toIndex].arrivalSeconds!!
        val inWindow = if (query.arriveBy) arrival <= query.seconds else departure >= query.seconds
        if (!inWindow) return null
        val midnight = train.serviceDate.atStartOfDay()
        return Itinerary(
            listOf(
                RideLeg(
                    trip = Trip(
                        number = train.number,
                        product = productName(train.product),
                        destination = train.destination ?: query.to.name,
                        serviceDate = train.serviceDate,
                        departure = midnight.plusSeconds(departure.toLong()),
                        arrival = midnight.plusSeconds(arrival.toLong()),
                        cancelled = true,
                        fromKey = train.stops[fromIndex].uic,
                        toKey = train.stops[toIndex].uic,
                    ),
                    fromName = query.from.name,
                    toName = query.to.name,
                    isBus = false,
                ),
            ),
        )
    }

    private fun productName(product: String?): String = when (product) {
        "highSpeedRail" -> "TGV"
        "longDistance", "interregionalRail" -> "Intercités"
        "regionalRail", "local" -> "TER"
        "suburbanRailway" -> "Transilien"
        "tramTrain" -> "Tram-train"
        "railShuttle" -> "Navette"
        else -> "Train"
    }

    /** Marks cancelled trains, and adds those the timetables do not have. */
    fun applyToNumberSearch(found: List<RideLeg>, feed: LiveFeed, query: String, date: LocalDate): List<RideLeg> {
        if (!feed.isFresh()) return found
        val wanted = query.trim().uppercase()
        val updated = found.map { ride ->
            if (feed.find(ride.trip)?.cancelled == true) ride.copy(trip = ride.trip.copy(cancelled = true)) else ride
        }
        val shown = updated.flatMap { TimetableMerger.numbers(it.trip.number) }.toSet()
        val extra = feed.trains
            .filter { it.serviceDate == date && it.number.uppercase().startsWith(wanted) && it.number !in shown }
            .mapNotNull(::wholeTrain)
        return (updated + extra).sortedWith(
            compareBy({ !TimetableMerger.numbers(it.trip.number).any { n -> n.equals(wanted, ignoreCase = true) } }, { it.trip.departure }),
        )
    }

    private fun wholeTrain(train: LiveTrain): RideLeg? {
        val departure = train.stops.firstOrNull()?.let { it.departureSeconds ?: it.arrivalSeconds } ?: return null
        val arrival = train.stops.last().let { it.arrivalSeconds ?: it.departureSeconds } ?: return null
        val midnight = train.serviceDate.atStartOfDay()
        val origin = train.origin ?: "Gare ${train.stops.first().uic}"
        val destination = train.destination ?: "Gare ${train.stops.last().uic}"
        return RideLeg(
            trip = Trip(
                number = train.number,
                product = productName(train.product),
                destination = destination,
                serviceDate = train.serviceDate,
                departure = midnight.plusSeconds(departure.toLong()),
                arrival = midnight.plusSeconds(arrival.toLong()),
                cancelled = train.cancelled,
            ),
            fromName = origin,
            toName = destination,
            isBus = false,
        )
    }

    private fun LiveFeed.isFresh() = java.time.OffsetDateTime.now(PARIS) < staleAfter

    private fun delayMinutes(epoch: Long?, planned: LocalDateTime, zone: ZoneId): Int {
        if (epoch == null) return 0
        return ((epoch - planned.atZone(zone).toEpochSecond()) / 60).toInt().coerceAtLeast(0)
    }
}
