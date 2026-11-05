package com.scresto.datrain.data

import java.text.Normalizer
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Corrections published with the timetables, so fixes do not need a new app version. */
data class TimetableCorrections(val sameStation: Map<String, String> = emptyMap())

/**
 * Combines SNCF and IDFM timetables on the phone: each source is published
 * separately, under its own licence.
 */
object TimetableMerger {
    /** Two closer stations with a similar name are one and the same. */
    private const val SAME_STATION_METRES = 700.0
    private const val WALK_METRES = 400.0
    private const val WALK_SECONDS = 900
    /** Estimate: large Paris stations are not linked in the timetables. */
    const val PARIS_TRANSFER_SECONDS = 3600
    private val PARIS_TERMINALS = intArrayOf(87391003, 87686006, 87113001, 87271007, 87547000, 87384008, 87686667)
    private val IGNORED_WORDS = setOf("gare", "de", "du", "la", "le", "les", "l", "d", "des", "sur", "en", "sncf", "rer")
    private val WORD = Regex("[a-z0-9]+")
    private val ACCENTS = Regex("\\p{Mn}+")

    fun merge(sncf: RailTimetable, idfm: RailTimetable, corrections: TimetableCorrections = TimetableCorrections()): RailTimetable {
        val first = minOf(sncf.firstDate, idfm.firstDate)
        val last = maxOf(sncf.lastDate, idfm.lastDate)
        val days = ChronoUnit.DAYS.between(first, last).toInt() + 1
        val width = (days + 7) / 8
        val sncfShift = ChronoUnit.DAYS.between(first, sncf.firstDate).toInt()
        val idfmShift = ChronoUnit.DAYS.between(first, idfm.firstDate).toInt()

        val sncfCalendars = Array(sncf.calendars.size) { shifted(sncf.calendars[it], sncf.dayCount, sncfShift, width) }
        val idfmCalendars = Array(idfm.calendars.size) { shifted(idfm.calendars[it], idfm.dayCount, idfmShift, width) }

        // Days already covered by the SNCF, number by number.
        val sncfDays = HashMap<String, ByteArray>(sncf.tripCount)
        for (t in 0 until sncf.tripCount) {
            for (number in numbers(sncf.strings[sncf.tripNumber[t]])) {
                val bits = sncfDays.getOrPut(number) { ByteArray(width) }
                val calendar = sncfCalendars[sncf.tripCalendar[t]]
                for (i in 0 until width) bits[i] = (bits[i].toInt() or calendar[i].toInt()).toByte()
            }
        }

        val stationMap = mapStations(sncf, idfm, corrections)
        val stationCount = sncf.stationCount + stationMap.added.size
        val stationIndex = stationMap.indexes

        val calendars = ArrayList<ByteArray>(sncfCalendars.size + idfmCalendars.size)
        calendars.addAll(sncfCalendars)
        val idfmCalendarStart = calendars.size
        calendars.addAll(idfmCalendars)

        // A train in both sources keeps its SNCF version on those days.
        val keptIdfm = IntArray(idfm.tripCount)
        val keptCalendars = IntArray(idfm.tripCount)
        var keptCount = 0
        for (t in 0 until idfm.tripCount) {
            val own = idfmCalendars[idfm.tripCalendar[t]]
            var remaining: ByteArray? = null
            for (number in numbers(idfm.strings[idfm.tripNumber[t]])) {
                val taken = sncfDays[number] ?: continue
                val bits = remaining ?: own.copyOf().also { remaining = it }
                for (i in 0 until width) bits[i] = (bits[i].toInt() and taken[i].toInt().inv()).toByte()
            }
            val calendar = remaining
            val index = when {
                calendar == null -> idfmCalendarStart + idfm.tripCalendar[t]
                calendar.all { it.toInt() == 0 } -> continue
                else -> calendars.size.also { calendars.add(calendar) }
            }
            keptIdfm[keptCount] = t
            keptCalendars[keptCount] = index
            keptCount++
        }

        val tripCount = sncf.tripCount + keptCount
        val stringShift = sncf.strings.size
        val patternShift = sncf.patternStations.size
        val tripPattern = sncf.tripPattern.copyOf(tripCount)
        val tripCalendar = sncf.tripCalendar.copyOf(tripCount)
        val tripNumber = sncf.tripNumber.copyOf(tripCount)
        val tripLabel = sncf.tripLabel.copyOf(tripCount)
        val tripMode = sncf.tripMode.copyOf(tripCount)
        val tripOffset = sncf.tripOffset.copyOf(tripCount)
        for (k in 0 until keptCount) {
            val t = keptIdfm[k]
            val at = sncf.tripCount + k
            tripPattern[at] = idfm.tripPattern[t] + patternShift
            tripCalendar[at] = keptCalendars[k]
            tripNumber[at] = idfm.tripNumber[t] + stringShift
            tripLabel[at] = idfm.tripLabel[t] + stringShift
            tripMode[at] = idfm.tripMode[t]
            tripOffset[at] = idfm.tripOffset[t] + sncf.times.size
        }

        val names = arrayOfNulls<String>(stationCount)
        val lat = sncf.stationLat.copyOf(stationCount)
        val lon = sncf.stationLon.copyOf(stationCount)
        val uic = sncf.stationUic.copyOf(stationCount)
        sncf.stationNames.copyInto(names)
        stationMap.added.forEachIndexed { i, source ->
            val at = sncf.stationCount + i
            names[at] = idfm.stationNames[source]
            lat[at] = idfm.stationLat[source]
            lon[at] = idfm.stationLon[source]
        }
        @Suppress("UNCHECKED_CAST")
        val stationNames = names as Array<String>

        return RailTimetable(
            firstDate = first,
            dayCount = days,
            strings = sncf.strings + idfm.strings,
            stationNames = stationNames,
            stationLat = lat,
            stationLon = lon,
            stationUic = uic,
            transfers = buildTransfers(stationCount, lat, lon, uic, idfm, stationIndex),
            calendars = calendars.toTypedArray(),
            patternStations = sncf.patternStations + Array(idfm.patternStations.size) { p ->
                val source = idfm.patternStations[p]
                IntArray(source.size) { stationIndex[source[it]] }
            },
            patternFlags = sncf.patternFlags + idfm.patternFlags,
            tripPattern = tripPattern,
            tripCalendar = tripCalendar,
            tripNumber = tripNumber,
            tripLabel = tripLabel,
            tripMode = tripMode,
            tripOffset = tripOffset,
            times = sncf.times + idfm.times,
        )
    }

    /** "142174-142175" is one train that changes number on the way. */
    fun numbers(text: String): List<String> = if ('-' in text) text.split('-') else listOf(text)

    private fun shifted(source: ByteArray, sourceDays: Int, shift: Int, width: Int): ByteArray {
        val bits = ByteArray(width)
        for (day in 0 until sourceDays) {
            if ((source[day shr 3].toInt() shr (day and 7)) and 1 == 1) {
                val target = day + shift
                bits[target shr 3] = (bits[target shr 3].toInt() or (1 shl (target and 7))).toByte()
            }
        }
        return bits
    }

    private class StationMap(val indexes: IntArray, val added: List<Int>)

    private fun mapStations(sncf: RailTimetable, idfm: RailTimetable, corrections: TimetableCorrections): StationMap {
        val cells = HashMap<Long, MutableList<Int>>()
        val words = arrayOfNulls<Set<String>>(sncf.stationCount)
        val byName = HashMap<String, Int>(sncf.stationCount)
        for (s in 0 until sncf.stationCount) {
            cells.getOrPut(cell(sncf.stationLat[s], sncf.stationLon[s], 50)) { ArrayList(1) }.add(s)
            byName.putIfAbsent(sncf.stationNames[s], s)
        }
        val indexes = IntArray(idfm.stationCount)
        val added = ArrayList<Int>()
        for (s in 0 until idfm.stationCount) {
            val name = idfm.stationNames[s]
            val forced = corrections.sameStation[name]?.let(byName::get)
            val match = forced ?: findClose(idfm.stationLat[s], idfm.stationLon[s], name, sncf, cells, words)
            if (match != null) {
                indexes[s] = match
            } else {
                indexes[s] = sncf.stationCount + added.size
                added.add(s)
            }
        }
        return StationMap(indexes, added)
    }

    private fun findClose(
        lat: Float,
        lon: Float,
        name: String,
        sncf: RailTimetable,
        cells: Map<Long, List<Int>>,
        words: Array<Set<String>?>,
    ): Int? {
        val own = normalise(name)
        if (own.isEmpty()) return null
        var best: Int? = null
        var bestDistance = SAME_STATION_METRES
        forNeighbours(lat, lon, 50) { key ->
            cells[key]?.forEach { other ->
                val distance = metres(lat, lon, sncf.stationLat[other], sncf.stationLon[other])
                if (distance < bestDistance) {
                    val theirs = words[other] ?: normalise(sncf.stationNames[other]).also { words[other] = it }
                    if (theirs.isNotEmpty() && (own.containsAll(theirs) || theirs.containsAll(own))) {
                        best = other
                        bestDistance = distance
                    }
                }
            }
        }
        return best
    }

    private fun buildTransfers(
        count: Int,
        lat: FloatArray,
        lon: FloatArray,
        uic: IntArray,
        idfm: RailTimetable,
        stationIndex: IntArray,
    ): Array<IntArray> {
        val walks = Array(count) { HashMap<Int, Int>(0) }

        val cells = HashMap<Long, MutableList<Int>>()
        for (s in 0 until count) cells.getOrPut(cell(lat[s], lon[s], 100)) { ArrayList(1) }.add(s)
        for (s in 0 until count) {
            forNeighbours(lat[s], lon[s], 100) { key ->
                cells[key]?.forEach { other ->
                    if (other != s && metres(lat[s], lon[s], lat[other], lon[other]) < WALK_METRES) {
                        walks[s][other] = WALK_SECONDS
                    }
                }
            }
        }

        val terminals = (0 until count).filter { uic[it] in PARIS_TERMINALS }
        for (a in terminals) for (b in terminals) if (a != b) walks[a][b] = PARIS_TRANSFER_SECONDS

        // Official times replace the rules above.
        for (s in 0 until idfm.stationCount) {
            val list = idfm.transfers[s]
            for (i in list.indices step 2) {
                val from = stationIndex[s]
                val to = stationIndex[list[i]]
                if (from != to) walks[from][to] = list[i + 1]
            }
        }
        return Array(count) { s ->
            val entries = walks[s]
            IntArray(entries.size * 2).also { out ->
                var i = 0
                for ((other, seconds) in entries) {
                    out[i++] = other
                    out[i++] = seconds
                }
            }
        }
    }

    fun foldAccents(text: String): String = ACCENTS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")

    internal fun normalise(name: String): Set<String> {
        val plain = foldAccents(name).replace("saint", "st")
        return WORD.findAll(plain).map { it.value }.filterNot { it in IGNORED_WORDS }.toSet()
    }

    private fun cell(lat: Float, lon: Float, perDegree: Int): Long = cellKey((lat * perDegree).roundToInt(), (lon * perDegree).roundToInt())

    private fun cellKey(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)

    private inline fun forNeighbours(lat: Float, lon: Float, perDegree: Int, block: (Long) -> Unit) {
        val x = (lat * perDegree).roundToInt()
        val y = (lon * perDegree).roundToInt()
        for (i in -1..1) for (j in -1..1) block(cellKey(x + i, y + j))
    }

    private fun metres(aLat: Float, aLon: Float, bLat: Float, bLon: Float): Double =
        111_000.0 * hypot((aLat - bLat).toDouble(), (aLon - bLon) * cos(Math.toRadians(aLat.toDouble())))
}
