package com.scresto.datrain.data

import java.time.LocalDate

/** Small network for tests. A train: number, then (station, arrival, departure) per stop, in minutes. */
internal class TableBuilder(private val first: LocalDate = LocalDate.of(2026, 11, 2), private val days: Int = 10) {
    private val stations = ArrayList<StationRow>()
    private val trips = ArrayList<TripRow>()
    private val walks = ArrayList<Triple<Int, Int, Int>>()

    private class StationRow(val name: String, val lat: Float, val lon: Float, val uic: Int)
    private class TripRow(
        val number: String,
        val label: String,
        val mode: Byte,
        val runningDays: List<Int>,
        val stops: List<Triple<Int, Int, Int>>,
        val flags: List<Int>,
    )

    fun station(name: String, lat: Float = 45f, lon: Float = 2f, uic: Int = 0): Int {
        stations += StationRow(name, lat, lon, uic)
        return stations.lastIndex
    }

    fun walk(from: Int, to: Int, seconds: Int) {
        walks += Triple(from, to, seconds)
    }

    fun trip(
        number: String,
        vararg stops: Triple<Int, Int, Int>,
        runningDays: List<Int> = (0 until days).toList(),
        label: String = "TER",
        mode: Byte = RailTimetable.MODE_TRAIN,
        flags: List<Int> = List(stops.size) { 3 },
    ) {
        trips += TripRow(number, label, mode, runningDays, stops.toList(), flags)
    }

    fun build(): RailTimetable {
        val strings = (trips.map { it.number } + trips.map { it.label }).distinct()
        val width = (days + 7) / 8
        val times = ArrayList<Int>()
        val offsets = IntArray(trips.size)
        trips.forEachIndexed { t, trip ->
            offsets[t] = times.size
            trip.stops.forEach { (_, arrival, departure) ->
                times += arrival * 60
                times += departure * 60
            }
        }
        val transfers = Array(stations.size) { s ->
            walks.filter { it.first == s }.flatMap { listOf(it.second, it.third) }.toIntArray()
        }
        return RailTimetable(
            firstDate = first,
            dayCount = days,
            strings = strings.toTypedArray(),
            stationNames = Array(stations.size) { stations[it].name },
            stationLat = FloatArray(stations.size) { stations[it].lat },
            stationLon = FloatArray(stations.size) { stations[it].lon },
            stationUic = IntArray(stations.size) { stations[it].uic },
            transfers = transfers,
            calendars = Array(trips.size) { t ->
                ByteArray(width).also { bits ->
                    trips[t].runningDays.forEach { day -> bits[day / 8] = (bits[day / 8].toInt() or (1 shl (day % 8))).toByte() }
                }
            },
            patternStations = Array(trips.size) { t -> trips[t].stops.map { it.first }.toIntArray() },
            patternFlags = Array(trips.size) { t -> ByteArray(trips[t].stops.size) { trips[t].flags[it].toByte() } },
            tripPattern = IntArray(trips.size) { it },
            tripCalendar = IntArray(trips.size) { it },
            tripNumber = IntArray(trips.size) { strings.indexOf(trips[it].number) },
            tripLabel = IntArray(trips.size) { strings.indexOf(trips[it].label) },
            tripMode = ByteArray(trips.size) { trips[it].mode },
            tripOffset = offsets,
            times = times.toIntArray(),
        )
    }
}

internal fun stop(station: Int, arrival: Int, departure: Int = arrival) = Triple(station, arrival, departure)
