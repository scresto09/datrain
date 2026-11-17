package com.scresto.datrain.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Serializable
data class RemovedStop(
    val name: String,
    val uic: Int,
    val lat: Float,
    val lon: Float,
    /** Seconds from midnight of the service day. */
    val arrival: Int,
    val departure: Int,
    /** As in the timetables: 1 = boarding allowed, 2 = alighting allowed. */
    val flags: Int,
)

/** A run that left the timetables after being published, as it was planned. [date]: its service day. */
@Serializable
data class RemovedRun(val number: String, val label: String, val mode: Int, val date: String, val stops: List<RemovedStop>) {
    val day: LocalDate get() = LocalDate.parse(date)
    val key: String get() = "$number@$date@${RemovedRuns.key(stops.first())}@${stops.first().departure}"
}

/**
 * SNCF takes trains cancelled in advance (strikes, works) out of its timetables, without saying so. Comparing
 * the timetables the phone had with the new ones finds them: they are kept, and put back as cancelled trips,
 * so that the search, the favorites, the widget and the map all show them as such.
 */
object RemovedRuns {
    /** Days compared, from today: adapted plans are published a few days ahead. */
    const val DAYS_AHEAD = 14L

    /** Published with the timetables, so that a new install knows them too. */
    const val PUBLISHED = "removed.json.xz"
    private val json = Json { ignoreUnknownKeys = true }

    /** Xz, which keeps no time: the same runs give the same file, and the same hash. */
    fun writePublished(runs: List<RemovedRun>): ByteArray =
        ByteArrayOutputStream().also { out ->
            // A small dictionary: the phone needs only that much memory to read it.
            XZOutputStream(out, LZMA2Options(6).apply { dictSize = 4 shl 20 }).use { it.write(json.encodeToString(runs).toByteArray()) }
        }.toByteArray()

    fun readPublished(data: ByteArray): List<RemovedRun> =
        json.decodeFromString(XZInputStream(data.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8))

    /**
     * The runs still cancelled with the [new] timetables (without removed runs): those known before, and those
     * of the [old] SNCF timetables gone from the new ones. Past days are dropped.
     */
    fun update(previous: List<RemovedRun>, old: RailTimetable?, new: RailTimetable, today: LocalDate): List<RemovedRun> {
        val served = Served(new)
        val kept = previous.filter { run ->
            val day = run.day
            day >= today && day <= new.lastDate && run.stops.size >= 2 &&
                !served.serves(run.number, key(run.stops.first()), key(run.stops.last()), run.stops.first().departure, day)
        }
        val found = old?.let { find(it, served, today) }.orEmpty()
        return (kept + found).distinctBy { it.key }
    }

    private fun find(old: RailTimetable, served: Served, today: LocalDate): List<RemovedRun> {
        val first = maxOf(today, old.firstDate, served.table.firstDate)
        val last = minOf(today.plusDays(DAYS_AHEAD), old.lastDate, served.table.lastDate)
        val found = ArrayList<RemovedRun>()
        val keys = Array(old.stationCount) { key(old, it) }
        var day = first
        while (day <= last) {
            val dayIndex = ChronoUnit.DAYS.between(old.firstDate, day).toInt()
            for (t in 0 until old.firstRemoved) {
                if (!old.runsOn(t, dayIndex)) continue
                val stations = old.patternStations[old.tripPattern[t]]
                if (stations.size < 2) continue
                val number = old.strings[old.tripNumber[t]]
                val departure = old.times[old.tripOffset[t] + 1]
                if (served.serves(number, keys[stations.first()], keys[stations.last()], departure, day)) continue
                found += run(old, t, day)
            }
            day = day.plusDays(1)
        }
        return found
    }

    private fun run(table: RailTimetable, trip: Int, day: LocalDate): RemovedRun {
        val stations = table.patternStations[table.tripPattern[trip]]
        val flags = table.patternFlags[table.tripPattern[trip]]
        val offset = table.tripOffset[trip]
        return RemovedRun(
            number = table.strings[table.tripNumber[trip]],
            label = table.strings[table.tripLabel[trip]],
            mode = table.tripMode[trip].toInt(),
            date = day.toString(),
            stops = stations.mapIndexed { i, s ->
                RemovedStop(
                    name = table.stationNames[s],
                    uic = table.stationUic[s],
                    lat = table.stationLat[s],
                    lon = table.stationLon[s],
                    arrival = table.times[offset + i * 2],
                    departure = table.times[offset + i * 2 + 1],
                    flags = flags[i].toInt(),
                )
            },
        )
    }

    /** The removed runs added after the trips of [table], on their own day only. */
    fun append(table: RailTimetable, runs: List<RemovedRun>): RailTimetable {
        require(table.firstRemoved == table.tripCount) { "Removed runs already added" }
        val usable = runs.filter { it.stops.size >= 2 && it.day >= table.firstDate && it.day <= table.lastDate }
        if (usable.isEmpty()) return table

        val strings = ArrayList(table.strings.asList())
        val stringIndex = HashMap<String, Int>(strings.size * 2)
        strings.forEachIndexed { i, s -> stringIndex.putIfAbsent(s, i) }
        fun string(text: String) = stringIndex.getOrPut(text) { strings.add(text); strings.lastIndex }

        val names = ArrayList(table.stationNames.asList())
        val lat = ArrayList(table.stationLat.asList())
        val lon = ArrayList(table.stationLon.asList())
        val uic = ArrayList(table.stationUic.asList())
        val stationIndex = HashMap<String, Int>(names.size * 2)
        for (s in names.indices) stationIndex.putIfAbsent(key(table, s), s)
        fun station(stop: RemovedStop) = stationIndex.getOrPut(key(stop)) {
            names.add(stop.name)
            lat.add(stop.lat)
            lon.add(stop.lon)
            uic.add(stop.uic)
            names.lastIndex
        }

        val width = (table.dayCount + 7) / 8
        val count = usable.size
        val times = table.times.copyOf(table.times.size + usable.sumOf { it.stops.size * 2 })
        var used = table.times.size
        val tripOffset = IntArray(count)
        val patternStations = ArrayList<IntArray>(count)
        val patternFlags = ArrayList<ByteArray>(count)
        val calendars = ArrayList<ByteArray>(count)
        usable.forEachIndexed { k, run ->
            tripOffset[k] = used
            for (stop in run.stops) {
                times[used++] = stop.arrival
                times[used++] = stop.departure
            }
            patternStations += IntArray(run.stops.size) { station(run.stops[it]) }
            patternFlags += ByteArray(run.stops.size) { run.stops[it].flags.toByte() }
            val day = ChronoUnit.DAYS.between(table.firstDate, run.day).toInt()
            calendars += ByteArray(width).also { it[day shr 3] = (1 shl (day and 7)).toByte() }
        }
        val numbers = IntArray(count) { string(usable[it].number) }
        val labels = IntArray(count) { string(usable[it].label) }
        val stationCount = names.size

        return RailTimetable(
            firstDate = table.firstDate,
            dayCount = table.dayCount,
            strings = strings.toTypedArray(),
            stationNames = names.toTypedArray(),
            stationLat = lat.toFloatArray(),
            stationLon = lon.toFloatArray(),
            stationUic = uic.toIntArray(),
            transfers = Array(stationCount) { table.transfers.getOrElse(it) { IntArray(0) } },
            calendars = table.calendars + calendars,
            patternStations = table.patternStations + patternStations,
            patternFlags = table.patternFlags + patternFlags,
            tripPattern = table.tripPattern + IntArray(count) { table.patternStations.size + it },
            tripCalendar = table.tripCalendar + IntArray(count) { table.calendars.size + it },
            tripNumber = table.tripNumber + numbers,
            tripLabel = table.tripLabel + labels,
            tripMode = table.tripMode + ByteArray(count) { usable[it].mode.toByte() },
            tripOffset = table.tripOffset + tripOffset,
            times = times,
            firstRemoved = table.tripCount,
        )
    }

    /** A station across timetable versions: its UIC code, or its name for the few without one. */
    internal fun key(table: RailTimetable, station: Int): String =
        table.stationUic[station].takeIf { it != 0 }?.let { "u$it" } ?: "n${table.stationNames[station]}"

    internal fun key(stop: RemovedStop): String = stop.uic.takeIf { it != 0 }?.let { "u$it" } ?: "n${stop.name}"

    /** Whether the new timetables still run a train, day by day. */
    private class Served(val table: RailTimetable) {
        private class Day(val byNumber: Map<String, List<Int>>, val routes: Set<String>)

        private val days = HashMap<LocalDate, Day>()
        private val keys = Array(table.stationCount) { key(table, it) }

        /**
         * Still run: a train of that number from its first to its last station, at any time, or one leaving
         * its first station at the same time for the same last station under another number.
         */
        fun serves(number: String, firstKey: String, lastKey: String, departure: Int, day: LocalDate): Boolean {
            val index = days.getOrPut(day) { index(day) }
            if ("$firstKey@$departure@$lastKey" in index.routes) return true
            return TimetableMerger.numbers(number).any { n ->
                index.byNumber[n].orEmpty().any { t ->
                    val stations = table.patternStations[table.tripPattern[t]]
                    val from = stations.indexOfFirst { keys[it] == firstKey }
                    from >= 0 && (stations.size - 1 downTo from + 1).any { keys[stations[it]] == lastKey }
                }
            }
        }

        private fun index(day: LocalDate): Day {
            val dayIndex = ChronoUnit.DAYS.between(table.firstDate, day).toInt()
            val byNumber = HashMap<String, MutableList<Int>>()
            val routes = HashSet<String>()
            for (t in 0 until table.firstRemoved) {
                if (!table.runsOn(t, dayIndex)) continue
                for (n in TimetableMerger.numbers(table.strings[table.tripNumber[t]])) byNumber.getOrPut(n) { ArrayList(2) } += t
                val stations = table.patternStations[table.tripPattern[t]]
                routes += "${keys[stations.first()]}@${table.times[table.tripOffset[t] + 1]}@${keys[stations.last()]}"
            }
            return Day(byNumber, routes)
        }
    }
}
