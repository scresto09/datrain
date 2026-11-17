package com.scresto.datrain.data

import com.scresto.datrain.widget.WidgetData
import com.scresto.datrain.widget.WidgetStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** Trains taken out of the timetables after being published: found, then shown as cancelled everywhere. */
class RemovedRunsTest {
    private val l = 87000001
    private val m = 87000002
    private val t = 87000003
    private val x = 87000004
    private val allDays = (0 until 10).toList()
    private val first: LocalDate = LocalDate.of(2026, 11, 2)
    private val today: LocalDate = first.plusDays(2)

    private fun TableBuilder.stations() = listOf(
        station("Luchon", uic = l),
        station("Muret", uic = m),
        station("Toulouse", uic = t),
        station("Montréjeau", uic = x),
    )

    private val old = TableBuilder().apply {
        val (sl, sm, st, sx) = stations()
        trip("872820", stop(sl, 360), stop(sm, 470), stop(st, 490))
        trip("872805", stop(st, 1060), stop(sx, 1180))
        trip("500", stop(sl, 600), stop(st, 700))
        trip("600", stop(sl, 800), stop(st, 900))
    }.build()

    /** Today: 872820 only from Muret, 872805 not at all, 500 ten minutes later, 600 renumbered 601. */
    private val new = TableBuilder().apply {
        val (sl, sm, st, sx) = stations()
        trip("872820", stop(sl, 360), stop(sm, 470), stop(st, 490), runningDays = allDays - 2)
        trip("872820", stop(sm, 481), stop(st, 500), runningDays = listOf(2))
        trip("872805", stop(st, 1060), stop(sx, 1180), runningDays = allDays - 2)
        trip("500", stop(sl, 610), stop(st, 710))
        trip("601", stop(sl, 800), stop(st, 900))
    }.build()

    private val removed = RemovedRuns.update(emptyList(), old, new, today)
    private val planner = JourneyPlanner(RemovedRuns.append(new, removed))
    private val table = planner.table

    private fun index(name: String) = table.stationNames.indexOf(name)

    @Test fun `runs gone from the new timetables are found, moved and renumbered ones are not`() {
        assertEquals(listOf("872820@$today", "872805@$today"), removed.map { "${it.number}@${it.date}" })
        val shortened = removed.first()
        assertEquals(listOf("Luchon", "Muret", "Toulouse"), shortened.stops.map { it.name })
        assertEquals(listOf(360 * 60, 470 * 60, 490 * 60), shortened.stops.map { it.departure })
    }

    @Test fun `runs known before are kept until their day is past or they run again`() {
        val again = removed.first().copy(date = today.plusDays(1).toString())
        val past = removed.last().copy(date = today.minusDays(1).toString())
        val stillGone = removed.last()
        val kept = RemovedRuns.update(listOf(again, past, stillGone), null, new, today)
        assertEquals(listOf(stillGone), kept)
    }

    @Test fun `only days within reach are compared`() {
        val gone = TableBuilder(days = 30).apply {
            val (sl, _, st, _) = stations()
            trip("700", stop(sl, 600), stop(st, 700), runningDays = (0 until 30).toList())
        }.build()
        val none = TableBuilder(days = 30).apply { stations() }.build()
        val found = RemovedRuns.update(emptyList(), gone, none, today)
        assertEquals((2..2 + RemovedRuns.DAYS_AHEAD.toInt()).map { first.plusDays(it.toLong()).toString() }, found.map { it.date })
    }

    @Test fun `added runs keep the stations, run on their day only and come after the others`() {
        assertEquals(new.tripCount, table.firstRemoved)
        assertEquals(new.tripCount + 2, table.tripCount)
        assertEquals(new.stationCount, table.stationCount)
        val trip = table.firstRemoved
        assertTrue(table.runsOn(trip, 2))
        assertFalse(table.runsOn(trip, 3))
        assertEquals(listOf("Luchon", "Muret", "Toulouse"), table.patternStations[table.tripPattern[trip]].map { table.stationNames[it] })
    }

    @Test fun `a station missing from the new timetables is added`() {
        val run = removed.last().let { it.copy(stops = it.stops + RemovedStop("Ailleurs", 87000009, 43f, 1f, 1200 * 60, 1200 * 60, 3)) }
        val added = RemovedRuns.append(new, listOf(run))
        assertEquals(new.stationCount + 1, added.stationCount)
        assertEquals("Ailleurs", added.stationNames.last())
        assertEquals(0, added.transfers.last().size)
    }

    @Test fun `the saved timetables keep which trips are cancelled`() {
        val file = File.createTempFile("merged", ".bin").apply { deleteOnExit() }
        TimetableCache.write(table, file)
        val read = TimetableCache.read(file)!!
        assertEquals(table.firstRemoved, read.firstRemoved)
        assertEquals(table.tripCount, read.tripCount)
    }

    @Test fun `the search shows the cancelled train at its time, and no journey uses it`() {
        val found = planner.search(index("Luchon"), index("Toulouse"), today, 0)
        assertEquals(listOf("872820" to true, "500" to false, "601" to false), found.map { it.rides.single().trip.number to it.cancelled })
        assertTrue(found.first().rides.single().trip.removed)
        // Through Toulouse, only cancelled trains would make it.
        assertTrue(planner.search(index("Luchon"), index("Montréjeau"), today, 0).isEmpty())
    }

    @Test fun `where the same number still runs, the search shows only that one`() {
        val found = planner.search(index("Muret"), index("Toulouse"), today, 0)
        assertEquals(listOf(today.atTime(8, 1)), found.filter { it.rides.single().trip.number == "872820" }.map { it.departure })
        assertFalse(found.any { it.cancelled })
    }

    @Test fun `the search by arrival time shows it too`() {
        val found = planner.searchArrivingBy(index("Luchon"), index("Toulouse"), today, 9 * 3600)
        assertEquals(listOf("872820"), found.map { it.rides.single().trip.number })
        assertTrue(found.single().cancelled)
    }

    @Test fun `the search by number shows the cancelled run beside the one still running`() {
        val found = planner.searchTrainNumber("8728", today)
        assertEquals(
            listOf("872820 06:00 true", "872820 08:01 false", "872805 17:40 true"),
            found.map { "${it.trip.number} ${it.trip.departure.toLocalTime()} ${it.trip.cancelled}" },
        )
    }

    @Test fun `a favorite's run cancelled in advance stays until its planned arrival, then the next one`() {
        val favorite = FavoriteTrain("872820", "TER", "Luchon", "Toulouse", l, t)
        val cancelled = planner.nextRun(favorite, today.atTime(5, 0))!!
        assertEquals(today.atTime(6, 0), cancelled.trip.departure)
        assertTrue(cancelled.trip.cancelled)
        val next = planner.nextRun(favorite, today.atTime(8, 30))!!
        assertEquals(today.plusDays(1).atTime(6, 0), next.trip.departure)
        assertFalse(next.trip.cancelled)
    }

    @Test fun `a favorite on the part still running is not cancelled`() {
        val run = planner.nextRun(FavoriteTrain("872820", "TER", "Muret", "Toulouse", m, t), today.atTime(5, 0))!!
        assertEquals(today.atTime(8, 1), run.trip.departure)
        assertFalse(run.trip.cancelled)
    }

    @Test fun `a run cancelled in advance is not followed as a late one`() {
        val favorite = FavoriteTrain("872820", "TER", "Luchon", "Toulouse", l, t)
        val now = today.atTime(9, 0)
        val candidates = FavoriteRuns.candidates(favorite, planner, now)
        val feed = LiveFeed(OffsetDateTime.now(), OffsetDateTime.now().plusHours(1), emptyList())
        for (chosen in listOf(FavoriteRuns.choose(candidates, null, null, now), FavoriteRuns.choose(candidates, feed, null, now))) {
            assertEquals(today.plusDays(1).atTime(6, 0), chosen!!.single().trip.departure)
        }
    }

    @Test fun `real time of another run with that number does not bring it back`() {
        val ride = planner.nextRun(FavoriteTrain("872820", "TER", "Luchon", "Toulouse", l, t), today.atTime(5, 0))!!
        val shortened = LiveTrain(
            "872820", "872820", today, cancelled = false, hasRealtime = true,
            stops = listOf(LiveStop("$m", null, 481 * 60, departureDelaySeconds = 600), LiveStop("$t", 500 * 60, null, 600)),
        )
        val feed = LiveFeed(OffsetDateTime.now(), OffsetDateTime.now().plusHours(1), listOf(shortened))
        val trip = RealtimeOverlay.apply(listOf(Itinerary(listOf(ride))), feed, emptyList()).single().rides.single().trip
        assertTrue(trip.cancelled)
        assertEquals(0, trip.departureDelayMinutes)
    }

    private val removed872805 get() = planner.nextRun(FavoriteTrain("872805", "TER", "Toulouse", "Montréjeau", t, x), today.atTime(9, 0))!!

    private fun feed(vararg trains: LiveTrain) = LiveFeed(OffsetDateTime.now(), OffsetDateTime.now().plusHours(1), trains.toList())

    private fun running872805(cancelled: Boolean = false) = LiveTrain(
        "872805", "872805", today, cancelled, hasRealtime = true,
        stops = listOf(LiveStop("$t", null, 1060 * 60, departureDelaySeconds = 300), LiveStop("$x", 1180 * 60, null, 300)),
    )

    @Test fun `running after all in the live feed, it shows as running with its delay`() {
        val trip = RealtimeOverlay.apply(listOf(Itinerary(listOf(removed872805))), feed(running872805()), emptyList()).single().rides.single().trip
        assertFalse(trip.cancelled)
        assertEquals(5, trip.departureDelayMinutes)
    }

    @Test fun `the shortened run's own SNCF update does not bring it back`() {
        val ride = planner.nextRun(FavoriteTrain("872820", "TER", "Luchon", "Toulouse", l, t), today.atTime(5, 0))!!
        val update = RealtimeTrip(
            "872820", today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), cancelled = false,
            stops = listOf(RealtimeStop("$m", null, 0), RealtimeStop("$t", 0, null)),
        )
        val trip = RealtimeOverlay.apply(listOf(Itinerary(listOf(ride))), null, listOf(update)).single().rides.single().trip
        assertTrue(trip.cancelled)
    }

    @Test fun `the search by number shows it running when the live feed has it`() {
        val found = planner.searchTrainNumber("872805", today)
        assertTrue(found.single().trip.cancelled)
        val updated = RealtimeOverlay.applyToNumberSearch(found, feed(running872805()), "872805", today)
        assertFalse(updated.single().trip.cancelled)
    }

    @Test fun `a board passage of that number at another time is not the cancelled run`() {
        val board = Board(today, OffsetDateTime.now().plusHours(1), listOf(Passage("872805", null, 1100 * 60, 0, 0, null, cancelled = false)))
        val now = OffsetDateTime.now()
        assertNull(BoardPatch.find(board, "872805", today, 1060 * 60, null, now, exact = true))
        assertEquals(1100 * 60, BoardPatch.find(board, "872805", today, 1060 * 60, null, now)!!.departureSeconds)
    }

    private class BoardLive(private val day: LocalDate, private val boards: Map<Int, Board>) : LiveSource {
        var feedReads = 0

        override suspend fun feed(): LiveFeed {
            feedReads++
            return LiveFeed(OffsetDateTime.now(), OffsetDateTime.now().plusHours(1), emptyList())
        }

        override suspend fun board(uic: Int): Board = boards[uic] ?: Board(day, OffsetDateTime.now().plusHours(1), emptyList())

        override suspend fun alerts(): List<ServiceAlert> = emptyList()

        override suspend fun tripUpdates(): List<RealtimeTrip> = emptyList()
    }

    @Test fun `the background check sees it running after all on its departure board, and tells it`() {
        val favorite = FavoriteTrain("872805", "TER", "Toulouse", "Montréjeau", t, x)
        val passage = Passage("872805", null, 1060 * 60, 0, 600, "2", cancelled = false)
        val live = BoardLive(today, mapOf(t to Board(today, OffsetDateTime.now().plusHours(1), listOf(passage))))
        val followed = mapOf(favorite.key to WatchState("${today}T17:40", "${today}T19:40"))

        val morning = check(favorite, today.atTime(9, 0), followed, live)
        assertTrue(morning.states.getValue(favorite.key).cancelled)

        val afternoon = check(favorite, today.atTime(15, 0), morning.states, live)
        val state = afternoon.states.getValue(favorite.key)
        assertFalse(state.cancelled)
        assertEquals(10, state.departureDelay)
        assertEquals("2", state.platform)
        assertTrue(FavoriteChange.Restored in afternoon.notices.single().changes)
        assertEquals(0, live.feedReads)
        // The widget follows what the check saw.
        val content = WidgetData.next(favorite, listOf(removed872805), listOf(state), today.atTime(15, 0))
        assertEquals(WidgetStatus.DELAYED, content.status)
    }

    @Test fun `without a passage at its time on the board, it stays cancelled`() {
        val favorite = FavoriteTrain("872805", "TER", "Toulouse", "Montréjeau", t, x)
        val other = Passage("872805", null, 1100 * 60, 0, 0, null, cancelled = false)
        val live = BoardLive(today, mapOf(t to Board(today, OffsetDateTime.now().plusHours(1), listOf(other))))
        val result = check(favorite, today.atTime(15, 0), emptyMap(), live)
        assertTrue(result.states.getValue(favorite.key).cancelled)
        assertEquals(0, live.feedReads)
    }

    @Test fun `back in the timetables the same day, it is told running again`() {
        val favorite = FavoriteTrain("872805", "TER", "Toulouse", "Montréjeau", t, x)
        val cancelledBefore = mapOf(favorite.key to WatchState("${today}T17:40", "${today}T19:40", cancelled = true, removed = true))
        val back = JourneyPlanner(old)
        val result = runBlocking {
            FavoriteCheck.run(listOf(favorite), { true }, back, cancelledBefore, emptySet(), CountingLive(today), today.atTime(9, 0))
        }
        val state = result.states.getValue(favorite.key)
        assertFalse(state.cancelled)
        assertFalse(state.removed)
        assertEquals(listOf<FavoriteChange>(FavoriteChange.Restored), result.notices.single().changes)
    }

    @Test fun `a train cancelled then running again in real time is told`() {
        val old = WatchState("${today}T17:40", "${today}T19:40", cancelled = true)
        assertEquals(listOf<FavoriteChange>(FavoriteChange.Restored), FavoriteWatch.changes(old, old.copy(cancelled = false), today.atTime(15, 0)))
    }

    @Test fun `a run moved the same day in the new timetables is told with its new time`() {
        val old = WatchState("${today}T10:00", "${today}T11:40")
        val moved = WatchState("${today}T10:10", "${today}T11:50")
        assertEquals(listOf<FavoriteChange>(FavoriteChange.ScheduleChanged(today.atTime(10, 10))), FavoriteWatch.changes(old, moved, today.atTime(8, 0)))
    }

    @Test fun `the widget shows it as cancelled, even before any check`() {
        val favorite = FavoriteTrain("872805", "TER", "Toulouse", "Montréjeau", t, x)
        val ride = planner.nextRun(favorite, today.atTime(9, 0))!!
        val content = WidgetData.next(favorite, listOf(ride), listOf(null), today.atTime(9, 0))
        assertEquals(WidgetStatus.CANCELLED, content.status)
        assertEquals("17:40", content.departure)
    }

    private class CountingLive(private val day: LocalDate) : LiveSource {
        var reads = 0

        override suspend fun feed(): LiveFeed {
            reads++
            return LiveFeed(OffsetDateTime.now(), OffsetDateTime.now().plusHours(1), emptyList())
        }

        override suspend fun board(uic: Int): Board {
            reads++
            return Board(day, OffsetDateTime.now().plusHours(1), emptyList())
        }

        override suspend fun alerts(): List<ServiceAlert> = emptyList()

        override suspend fun tripUpdates(): List<RealtimeTrip> {
            reads++
            return emptyList()
        }
    }

    private fun check(favorite: FavoriteTrain, now: LocalDateTime, saved: Map<String, WatchState>, live: LiveSource) =
        runBlocking { FavoriteCheck.run(listOf(favorite), { true }, planner, saved, emptySet(), live, now) }

    @Test fun `the background check tells it once, without reading real time, then moves on after its arrival`() {
        val favorite = FavoriteTrain("872805", "TER", "Toulouse", "Montréjeau", t, x)
        val live = CountingLive(today)
        val followed = mapOf(favorite.key to WatchState("${today}T17:40", "${today}T19:40"))

        val first = check(favorite, today.atTime(9, 0), followed, live)
        val state = first.states.getValue(favorite.key)
        assertEquals("${today}T17:40", state.departure)
        assertTrue(state.cancelled)
        assertEquals(listOf<FavoriteChange>(FavoriteChange.Cancelled), first.notices.single().changes)
        assertEquals(0, live.reads)

        val again = check(favorite, today.atTime(12, 0), first.states, live)
        assertTrue(again.notices.isEmpty())

        val after = check(favorite, today.atTime(20, 0), again.states, live)
        assertEquals("${today.plusDays(1)}T17:40", after.states.getValue(favorite.key).departure)
        assertFalse(after.states.getValue(favorite.key).cancelled)
        assertTrue(after.notices.isEmpty())
    }
}
