package com.scresto.datrain.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One train, or a walk between two stations if [trip] is -1.
 * Times count seconds from midnight of the search day.
 * [dayOffset]: gap between the search day and the train's service day.
 */
data class JourneyLeg(
    val trip: Int,
    val fromStation: Int,
    val toStation: Int,
    val departure: Int,
    val arrival: Int,
    val dayOffset: Int = 0,
)

data class Journey(val legs: List<JourneyLeg>) {
    val rides = legs.filter { it.trip >= 0 }
    val departure get() = rides.first().departure
    val arrival get() = legs.last().arrival
    val changes get() = rides.size - 1
}

/**
 * Search for one day, with trains that left the day before and arrivals the day after.
 *
 * Train segments are scanned by departure time, once per allowed number of trains.
 * This also gives journeys with fewer changes that arrive later than the fastest one.
 * An instance cannot run two searches at once.
 */
class JourneySearch(private val table: RailTimetable, date: LocalDate) {
    private val connDeparture: IntArray
    private val connArrival: IntArray
    private val connFrom: IntArray
    private val connTo: IntArray
    private val connRun: IntArray
    private val connFlags: ByteArray
    private val runTrip: IntArray
    private val runOffset: IntArray

    init {
        val dayIndex = ChronoUnit.DAYS.between(table.firstDate, date).toInt()
        val runs = ArrayList<Long>()
        var total = 0
        for (t in 0 until table.tripCount) {
            val stopCount = table.patternStations[table.tripPattern[t]].size
            val endsAfterMidnight = table.times[table.tripOffset[t] + stopCount * 2 - 2] > DAY
            for (offset in -1..1) {
                if (offset == -1 && !endsAfterMidnight) continue
                if (table.runsOn(t, dayIndex + offset)) {
                    runs.add((t.toLong() shl 8) or (offset + 1).toLong())
                    total += table.patternStations[table.tripPattern[t]].size - 1
                }
            }
        }
        val keys = LongArray(total)
        val departure = IntArray(total)
        val arrival = IntArray(total)
        val from = IntArray(total)
        val to = IntArray(total)
        val run = IntArray(total)
        val flags = ByteArray(total)
        runTrip = IntArray(runs.size)
        runOffset = IntArray(runs.size)
        var n = 0
        runs.forEachIndexed { r, packed ->
            val t = (packed shr 8).toInt()
            val offset = (packed and 255).toInt() - 1
            val shift = offset * DAY
            runTrip[r] = t
            runOffset[r] = offset
            val stations = table.patternStations[table.tripPattern[t]]
            val stopFlags = table.patternFlags[table.tripPattern[t]]
            var at = table.tripOffset[t]
            for (s in 0 until stations.size - 1) {
                departure[n] = table.times[at + 1] + shift
                arrival[n] = table.times[at + 2] + shift
                from[n] = stations[s]
                to[n] = stations[s + 1]
                run[n] = r
                flags[n] = ((stopFlags[s].toInt() and CAN_BOARD) or (stopFlags[s + 1].toInt() and CAN_ALIGHT)).toByte()
                keys[n] = (departure[n].toLong() + DAY) shl 32 or n.toLong()
                n++
                at += 2
            }
        }
        keys.sort()
        connDeparture = IntArray(total) { departure[keys[it].toInt()] }
        connArrival = IntArray(total) { arrival[keys[it].toInt()] }
        connFrom = IntArray(total) { from[keys[it].toInt()] }
        connTo = IntArray(total) { to[keys[it].toInt()] }
        connRun = IntArray(total) { run[keys[it].toInt()] }
        connFlags = ByteArray(total) { flags[keys[it].toInt()] }
    }

    val connectionCount get() = connDeparture.size

    private val ready = Array(MAX_RIDES + 1) { IntArray(table.stationCount) }
    private val parentBoard = Array(MAX_RIDES + 1) { IntArray(table.stationCount) }
    private val parentAlight = Array(MAX_RIDES + 1) { IntArray(table.stationCount) }
    private val parentWalk = Array(MAX_RIDES + 1) { IntArray(table.stationCount) }
    private val parentLevel = Array(MAX_RIDES + 1) { IntArray(table.stationCount) }
    private val boarded = Array(MAX_RIDES + 1) { IntArray(runTrip.size) }

    /** Worthwhile journeys leaving between [start] and [until] seconds. */
    fun search(from: Int, to: Int, start: Int, until: Int = DAY): List<Journey> {
        val found = ArrayList<Journey>()
        var clock = start
        var runs = 0
        while (clock < until && runs < MAX_SCANS) {
            runs++
            val batch = earliest(from, to, clock, until)
            if (batch.isEmpty()) break
            found.addAll(batch)
            clock = batch.minOf { it.departure } + 60
        }
        return keepBest(found.filter { it.departure < until })
    }

    private fun keepBest(all: List<Journey>): List<Journey> {
        val unique = all.distinctBy { j -> j.rides.map { it.trip to it.departure } }
        return unique
            .filter { j -> unique.none { o -> o !== j && o.beats(j) } }
            .sortedWith(compareBy({ it.departure }, { it.arrival }))
    }

    /** True if [other] is pointless next to this journey. */
    private fun Journey.beats(other: Journey): Boolean {
        if (departure < other.departure || changes > other.changes) return false
        val strictlyBetter = departure > other.departure || arrival < other.arrival || changes < other.changes
        // One more change must save enough time to be worth it.
        val allowance = (other.changes - changes) * CHANGE_WORTH
        return arrival <= other.arrival + allowance && strictlyBetter
    }

    /** Earliest arrival for each allowed number of trains, from a given time. */
    private fun earliest(from: Int, to: Int, start: Int, until: Int): List<Journey> {
        for (level in 0..MAX_RIDES) {
            ready[level].fill(NEVER)
            parentWalk[level].fill(-1)
            boarded[level].fill(-1)
        }
        ready[0][from] = start
        val walksFromOrigin = table.transfers[from]
        for (i in walksFromOrigin.indices step 2) {
            ready[0][walksFromOrigin[i]] = start + walksFromOrigin[i + 1]
            parentWalk[0][walksFromOrigin[i]] = from
        }

        var first = lowerBound(start)
        val size = connDeparture.size
        // Beyond this, a journey would leave late enough to be found by the next scan.
        // The margin lets trains already boarded arrive: slower, but with fewer changes.
        var bestArrival = NEVER
        while (first < size) {
            val c = first++
            val departure = connDeparture[c]
            if (bestArrival == NEVER) {
                // A train leaving before the limit can arrive long after: midnight must not cut it.
                if (departure >= until + LATE_ARRIVALS) break
            } else if (departure >= bestArrival + FEWER_CHANGES_MARGIN) {
                break
            }
            val origin = connFrom[c]
            val run = connRun[c]
            val flags = connFlags[c].toInt()
            for (level in 1..MAX_RIDES) {
                val runBoarded = boarded[level]
                if (runBoarded[run] < 0) {
                    val availableAt = ready[level - 1][origin]
                    if (flags and CAN_BOARD == 0 || availableAt > departure) continue
                    if (level > 1 && departure - availableAt > MAX_WAIT) continue
                    runBoarded[run] = c
                }
                if (flags and CAN_ALIGHT == 0) continue
                val arrival = connArrival[c]
                val stop = connTo[c]
                val board = runBoarded[run]
                if (stop == to && arrival < bestArrival) bestArrival = arrival
                if (stop == from) continue
                setReady(level, stop, arrival + MIN_CHANGE, board, c, -1)
                val walks = table.transfers[stop]
                for (i in walks.indices step 2) {
                    if (walks[i] != from) setReady(level, walks[i], arrival + walks[i + 1], board, c, stop)
                }
            }
        }

        val journeys = ArrayList<Journey>()
        for (level in 1..MAX_RIDES) {
            if (ready[level][to] == NEVER || parentLevel[level][to] != level) continue
            rebuild(from, to, level)?.let(journeys::add)
        }
        return journeys
    }

    private fun setReady(level: Int, stop: Int, time: Int, board: Int, alight: Int, walkFrom: Int) {
        for (higher in level..MAX_RIDES) {
            if (time >= ready[higher][stop]) continue
            ready[higher][stop] = time
            parentBoard[higher][stop] = board
            parentAlight[higher][stop] = alight
            parentWalk[higher][stop] = walkFrom
            parentLevel[higher][stop] = level
        }
    }

    private fun rebuild(from: Int, to: Int, startLevel: Int): Journey? {
        val legs = ArrayList<JourneyLeg>()
        var stop = to
        var level = startLevel
        var guard = 0
        while (guard++ < MAX_RIDES * 3) {
            if (level == 0) {
                val walkFrom = parentWalk[0][stop]
                if (stop == from) break
                if (walkFrom < 0) return null
                legs.add(JourneyLeg(-1, walkFrom, stop, 0, 0))
                stop = walkFrom
                continue
            }
            val actual = parentLevel[level][stop]
            val board = parentBoard[level][stop]
            val alight = parentAlight[level][stop]
            val walkFrom = parentWalk[level][stop]
            val alightStop = connTo[alight]
            if (walkFrom >= 0 && stop != alightStop) {
                legs.add(JourneyLeg(-1, walkFrom, stop, connArrival[alight], ready[level][stop]))
            }
            legs.add(
                JourneyLeg(
                    runTrip[connRun[alight]], connFrom[board], alightStop,
                    connDeparture[board], connArrival[alight], runOffset[connRun[alight]],
                ),
            )
            stop = connFrom[board]
            level = actual - 1
            if (level == 0 && stop == from) break
        }
        if (legs.isEmpty()) return null
        return Journey(legs.reversed())
    }

    private fun lowerBound(time: Int): Int {
        var low = 0
        var high = connDeparture.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (connDeparture[middle] < time) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        const val DAY = 86400
        private const val CAN_BOARD = 1
        private const val CAN_ALIGHT = 2
        private const val NEVER = Int.MAX_VALUE
        const val MIN_CHANGE = 600
        /** Four changes at most. */
        const val MAX_RIDES = 5
        private const val MAX_SCANS = 60
        private const val FEWER_CHANGES_MARGIN = 3 * 3600
        private const val LATE_ARRIVALS = 12 * 3600
        /** Nobody waits a whole day for a connection. */
        private const val MAX_WAIT = 6 * 3600
        private const val CHANGE_WORTH = 20 * 60
    }
}
