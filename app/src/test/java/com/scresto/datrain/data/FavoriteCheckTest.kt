package com.scresto.datrain.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** The scenarios that went wrong on a real phone, replayed with prepared real time. */
class FavoriteCheckTest {
    private val a = 87000001
    private val m = 87000003
    private val c = 87000002
    private val planner = JourneyPlanner(
        TableBuilder().apply {
            val sa = station("A", uic = a)
            val sc = station("C", uic = c)
            val sm = station("M", uic = m)
            trip("1", stop(sa, 480), stop(sc, 540))
            trip("2", stop(sa, 1436), stop(sc, 1474))
            trip("4", stop(sa, 480), stop(sm, 540))
            trip("3", stop(sm, 550), stop(sc, 600))
        }.build(),
    )
    private val day: LocalDate = planner.table.firstDate.plusDays(2)
    private val fresh: OffsetDateTime = OffsetDateTime.now().plusHours(1)
    private val direct = FavoriteTrain("1", "TER", "A", "C", a, c)

    private class FakeLive(
        val trains: List<LiveTrain> = emptyList(),
        val boards: Map<Int, Board> = emptyMap(),
        val offline: Boolean = false,
        val day: LocalDate,
        val fresh: OffsetDateTime,
        val updates: List<RealtimeTrip> = emptyList(),
    ) : LiveSource {
        var feedReads = 0

        override suspend fun feed(): LiveFeed {
            feedReads++
            if (offline) throw NomadeError.Network
            return LiveFeed(OffsetDateTime.now(), fresh, trains)
        }

        override suspend fun board(uic: Int): Board {
            if (offline) throw NomadeError.Network
            return boards[uic] ?: Board(day, fresh, emptyList())
        }

        override suspend fun alerts(): List<ServiceAlert> = if (offline) throw NomadeError.Network else emptyList()

        override suspend fun tripUpdates(): List<RealtimeTrip> = if (offline) throw NomadeError.Network else updates
    }

    private fun live(vararg trains: LiveTrain, boards: Map<Int, Board> = emptyMap(), offline: Boolean = false) =
        FakeLive(trains.toList(), boards, offline, day, fresh)

    private fun train(number: String, cancelled: Boolean = false, arrivalDelay: Int = 0) =
        LiveTrain(number, number, day, cancelled, true, listOf(LiveStop("$a", null, 28800), LiveStop("$c", 32400, null, arrivalDelay)))

    private fun check(favorite: FavoriteTrain, now: LocalDateTime, source: LiveSource, saved: Map<String, WatchState> = emptyMap()) =
        runBlocking { FavoriteCheck.run(listOf(favorite), { true }, planner, saved, emptySet(), source, now) }

    @Test fun `a favorite added while its train runs late after its planned arrival follows that train`() {
        val result = check(direct, day.atTime(9, 30), live(train("1", arrivalDelay = 3600)))
        val state = result.states.getValue(direct.key)
        assertEquals("${day}T08:00", state.departure)
        assertEquals(60, state.delayMinutes)
        assertTrue(result.notices.isEmpty())
    }

    @Test fun `going back from tomorrow's run to today's late one announces no cancellation`() {
        val tomorrow = WatchState("${day.plusDays(1)}T08:00", "${day.plusDays(1)}T09:00")
        val result = check(direct, day.atTime(9, 30), live(train("1", arrivalDelay = 3600)), mapOf(direct.key to tomorrow))
        assertEquals("${day}T08:00", result.states.getValue(direct.key).departure)
        assertTrue(result.notices.isEmpty())
    }

    @Test fun `asked by the favorites list, the full feed gives both delays as the list shows them`() {
        val saved = mapOf(direct.key to WatchState("${day}T08:00", "${day}T09:00", fullChecked = true))
        // The departure board has the train on time, and says nothing of the arrival.
        val board = Board(day, fresh, listOf(Passage("1", null, 28800, 0, 0, null, cancelled = false)))
        val source = live(train("1", arrivalDelay = 360), boards = mapOf(a to board))
        val fromBoard = check(direct, day.atTime(7, 30), source, saved).states.getValue(direct.key)
        assertEquals(0, fromBoard.arrivalDelay)
        val fromFeed = runBlocking {
            FavoriteCheck.run(listOf(direct), { true }, planner, saved, emptySet(), source, day.atTime(7, 30), force = true, fullFeed = true)
        }
        val state = fromFeed.states.getValue(direct.key)
        assertEquals(0, state.departureDelay)
        assertEquals(6, state.arrivalDelay)
    }

    @Test fun `a departure delay seen on the board brings the arrival delay from the full feed`() {
        val saved = mapOf(direct.key to WatchState("${day}T08:00", "${day}T09:00", fullChecked = true, departureDelayMinutes = 0, arrivalDelayMinutes = 0))
        val late = Board(day, fresh, listOf(Passage("1", null, 28800, 0, 600, null, cancelled = false)))
        val feedTrain = LiveTrain("1", "1", day, false, true, listOf(LiveStop("$a", null, 28800, departureDelaySeconds = 600), LiveStop("$c", 32400, null, 720)))
        val state = check(direct, day.atTime(5, 30), live(feedTrain, boards = mapOf(a to late)), saved).states.getValue(direct.key)
        assertEquals(10, state.departureDelay)
        assertEquals(12, state.arrivalDelay)
    }

    @Test fun `an unchanged departure delay on the board does not read the full feed`() {
        val saved = mapOf(direct.key to WatchState("${day}T08:00", "${day}T09:00", fullChecked = true, departureDelayMinutes = 0, arrivalDelayMinutes = 0))
        val onTime = Board(day, fresh, listOf(Passage("1", null, 28800, 0, 0, null, cancelled = false)))
        val source = live(train("1", arrivalDelay = 360), boards = mapOf(a to onTime))
        check(direct, day.atTime(5, 30), source, saved)
        assertEquals(0, source.feedReads)
    }

    @Test fun `a train already cancelled at the first look is announced`() {
        val result = check(direct, day.atTime(7, 0), live(train("1", cancelled = true)))
        assertEquals(listOf(FavoriteChange.Cancelled), result.notices.single().changes)
    }

    @Test fun `after midnight the next day's board gives the delay of yesterday evening's train`() {
        val late = FavoriteTrain("2", "TER", "A", "C", a, c)
        val saved = mapOf(late.key to WatchState("${day}T23:56", "${day.plusDays(1)}T00:34", fullChecked = true))
        val board = Board(day.plusDays(1), fresh, listOf(Passage("2", 2040, null, 420, 0, null, cancelled = false)))
        val result = check(late, day.plusDays(1).atTime(0, 20), live(boards = mapOf(c to board)), saved)
        assertEquals(7, result.states.getValue(late.key).delayMinutes)
        assertEquals(listOf(FavoriteChange.Delayed(null, 7)), result.notices.single().changes)
    }

    @Test fun `a delay on the first train of a journey announces the change at risk`() {
        val journey = FavoriteTrain("4", "TER", "A", "M", a, m, connections = listOf(FavoriteTrain("3", "TER", "M", "C", m, c)))
        val keys = FavoriteWatch.legKeys(journey)
        val saved = mapOf(
            keys[0] to WatchState("${day}T08:00", "${day}T09:00", fullChecked = true),
            keys[1] to WatchState("${day}T09:10", "${day}T10:00", fullChecked = true),
        )
        val first = LiveTrain("4", "4", day, false, true, listOf(LiveStop("$a", null, 28800), LiveStop("$m", 32400, null, 480)))
        val second = LiveTrain("3", "3", day, false, true, listOf(LiveStop("$m", null, 33000), LiveStop("$c", 36000, null)))
        val result = check(journey, day.atTime(8, 30), live(first, second), saved)
        assertTrue(result.notices.any { notice -> notice.changes == listOf(FavoriteChange.ConnectionAtRisk("M", 2)) })
    }

    @Test fun `without network nothing is saved and the late run is looked for again next time`() {
        val result = check(direct, day.atTime(9, 30), live(offline = true))
        assertTrue(result.states.isEmpty())
        assertTrue(result.notices.isEmpty())
        assertTrue(result.lateChecked.isEmpty())
    }
}
