package com.scresto.datrain.data

import java.io.DataInputStream
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.GZIPInputStream

/**
 * Timetable of one source, or of both once combined by [TimetableMerger].
 *
 * Files built by tools/build_timetable.py, big-endian: "DTRN", version:int,
 * first day:int (yyyymmdd), days:short, then strings, stations (name, lat:float, lon:float, uic:int),
 * walks (from:int, to:int, seconds:short), calendars (one bit per day),
 * patterns (size:short, then station:int and flags:byte per stop, 1 = boarding allowed, 2 = alighting allowed),
 * and trips as varints (pattern, calendar, number, commercial name, mode:byte, first departure,
 * then for each next stop: travel seconds, dwell seconds).
 */
class RailTimetable(
    val firstDate: LocalDate,
    val dayCount: Int,
    val strings: Array<String>,
    val stationNames: Array<String>,
    val stationLat: FloatArray,
    val stationLon: FloatArray,
    val stationUic: IntArray,
    /** For each station, pairs of (other station, walking seconds). */
    val transfers: Array<IntArray>,
    val calendars: Array<ByteArray>,
    val patternStations: Array<IntArray>,
    val patternFlags: Array<ByteArray>,
    val tripPattern: IntArray,
    val tripCalendar: IntArray,
    val tripNumber: IntArray,
    val tripLabel: IntArray,
    val tripMode: ByteArray,
    /** Position in [times] of each trip's first stop. Each stop takes two values: arrival, departure. */
    val tripOffset: IntArray,
    val times: IntArray,
) {
    val stationCount get() = stationNames.size
    val tripCount get() = tripPattern.size
    val lastDate: LocalDate get() = firstDate.plusDays(dayCount - 1L)

    fun runsOn(trip: Int, dayIndex: Int): Boolean {
        if (dayIndex < 0 || dayIndex >= dayCount) return false
        val bits = calendars[tripCalendar[trip]]
        return (bits[dayIndex shr 3].toInt() shr (dayIndex and 7)) and 1 == 1
    }

    companion object {
        const val MODE_TRAIN: Byte = 0
        const val MODE_BUS: Byte = 1
        const val MODE_SUBURBAN: Byte = 2

        private const val VERSION = 2

        fun readGzip(source: InputStream): RailTimetable =
            read(GZIPInputStream(source.buffered(), 65536).buffered(65536))

        fun read(source: InputStream): RailTimetable {
            val input = DataInputStream(source)
            val magic = ByteArray(4).also(input::readFully)
            require(String(magic, Charsets.US_ASCII) == "DTRN") { "Invalid timetable file" }
            val version = input.readInt()
            require(version == VERSION) { "Unsupported timetable version: $version" }
            val rawDate = input.readInt()
            val first = LocalDate.of(rawDate / 10000, rawDate / 100 % 100, rawDate % 100)
            val days = input.readUnsignedShort()

            val strings = Array(input.readInt()) { input.readText() }

            val stationCount = input.readInt()
            val names = arrayOfNulls<String>(stationCount)
            val lat = FloatArray(stationCount)
            val lon = FloatArray(stationCount)
            val uic = IntArray(stationCount)
            for (i in 0 until stationCount) {
                names[i] = input.readText()
                lat[i] = input.readFloat()
                lon[i] = input.readFloat()
                uic[i] = input.readInt()
            }

            val walks = Array(stationCount) { ArrayList<Int>(0) }
            repeat(input.readInt()) {
                val from = input.readInt()
                val to = input.readInt()
                walks[from].add(to)
                walks[from].add(input.readUnsignedShort())
            }

            val width = (days + 7) / 8
            val calendars = Array(input.readInt()) { ByteArray(width).also(input::readFully) }

            val patternCount = input.readInt()
            val patternStations = arrayOfNulls<IntArray>(patternCount)
            val patternFlags = arrayOfNulls<ByteArray>(patternCount)
            for (p in 0 until patternCount) {
                val size = input.readUnsignedShort()
                val stations = IntArray(size)
                val flags = ByteArray(size)
                for (i in 0 until size) {
                    stations[i] = input.readInt()
                    flags[i] = input.readByte()
                }
                patternStations[p] = stations
                patternFlags[p] = flags
            }

            val tripCount = input.readInt()
            val tripPattern = IntArray(tripCount)
            val tripCalendar = IntArray(tripCount)
            val tripNumber = IntArray(tripCount)
            val tripLabel = IntArray(tripCount)
            val tripMode = ByteArray(tripCount)
            val tripOffset = IntArray(tripCount)
            var times = IntArray(1 shl 21)
            var used = 0
            for (t in 0 until tripCount) {
                val pattern = input.readVarint()
                tripPattern[t] = pattern
                tripCalendar[t] = input.readVarint()
                tripNumber[t] = input.readVarint()
                tripLabel[t] = input.readVarint()
                tripMode[t] = input.readByte()
                val stops = patternStations[pattern]!!.size
                if (used + stops * 2 > times.size) times = times.copyOf(times.size * 2)
                tripOffset[t] = used
                var clock = input.readVarint()
                times[used++] = clock
                times[used++] = clock
                for (s in 1 until stops) {
                    clock += input.readVarint()
                    times[used++] = clock
                    clock += input.readVarint()
                    times[used++] = clock
                }
            }

            @Suppress("UNCHECKED_CAST")
            return RailTimetable(
                firstDate = first,
                dayCount = days,
                strings = strings,
                stationNames = names as Array<String>,
                stationLat = lat,
                stationLon = lon,
                stationUic = uic,
                transfers = Array(stationCount) { walks[it].toIntArray() },
                calendars = calendars,
                patternStations = patternStations as Array<IntArray>,
                patternFlags = patternFlags as Array<ByteArray>,
                tripPattern = tripPattern,
                tripCalendar = tripCalendar,
                tripNumber = tripNumber,
                tripLabel = tripLabel,
                tripMode = tripMode,
                tripOffset = tripOffset,
                times = times.copyOf(used),
            )
        }

        private fun DataInputStream.readText(): String {
            val raw = ByteArray(readUnsignedShort())
            readFully(raw)
            return String(raw, Charsets.UTF_8)
        }

        private fun DataInputStream.readVarint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = readUnsignedByte()
                result = result or ((b and 127) shl shift)
                if (b and 128 == 0) return result
                shift += 7
            }
        }
    }
}
