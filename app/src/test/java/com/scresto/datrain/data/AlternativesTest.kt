package com.scresto.datrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** Trains offered in place of a cancelled one, and the temporary follows taken from them. */
class AlternativesTest {
    private val a = 87000001
    private val b = 87000002
    private val c = 87000003
    private val planner = JourneyPlanner(
        TableBuilder().apply {
            val sa = station("A", uic = a)
            val sb = station("B", uic = b)
            val sc = station("C", uic = c)
            trip("10", stop(sa, 420), stop(sc, 480))
            trip("11", stop(sa, 480), stop(sc, 540))
            trip("12", stop(sa, 540), stop(sc, 600))
            trip("20", stop(sa, 490), stop(sb, 510))
            trip("21", stop(sb, 525), stop(sc, 535))
        }.build(),
    )
    private val day: LocalDate = planner.table.firstDate.plusDays(2)
    private val favorite = FavoriteTrain("11", "TER", "A", "C", a, c)
    private val cancelled = planner.nextRun(favorite, day.atTime(6, 0))!!

    private fun numbers(itinerary: Itinerary?) = itinerary?.rides?.map { it.trip.number }

    private fun feed(vararg trains: LiveTrain) = LiveFeed(OffsetDateTime.now(), OffsetDateTime.now().plusHours(1), trains.toList())

    private fun live(number: String, departure: Int, arrival: Int, delayMinutes: Int = 0, cancelled: Boolean = false) = LiveTrain(
        number, number, day, cancelled, hasRealtime = true,
        stops = listOf(LiveStop("$a", null, departure * 60, departureDelaySeconds = delayMinutes * 60), LiveStop("$c", arrival * 60, null, delayMinutes * 60)),
    )

    @Test fun `the train before and the one after, a change only when it arrives earlier`() {
        val found = Alternatives.find(planner, favorite, listOf(cancelled), day.atTime(6, 30))
        assertEquals(listOf("10"), numbers(found.before))
        // The direct 12 arrives at 10:00, the change through B at 08:55.
        assertEquals(listOf("20", "21"), numbers(found.after))
    }

    @Test fun `a direct train is kept when no change arrives earlier`() {
        val direct = JourneyPlanner(
            TableBuilder().apply {
                val sa = station("A", uic = a)
                val sb = station("B", uic = b)
                val sc = station("C", uic = c)
                trip("11", stop(sa, 480), stop(sc, 540))
                trip("12", stop(sa, 540), stop(sc, 600))
                trip("20", stop(sa, 490), stop(sb, 560))
                trip("21", stop(sb, 565), stop(sc, 620))
            }.build(),
        )
        val run = direct.nextRun(favorite, day.atTime(6, 0))!!
        assertEquals(listOf("12"), numbers(Alternatives.find(direct, favorite, listOf(run), day.atTime(6, 30)).after))
    }

    @Test fun `the train before is offered until it leaves, its delay included`() {
        assertNull(Alternatives.find(planner, favorite, listOf(cancelled), day.atTime(7, 5)).before)
        val late = Alternatives.find(planner, favorite, listOf(cancelled), day.atTime(7, 5), feed(live("10", 420, 480, delayMinutes = 10)))
        assertEquals(listOf("10"), numbers(late.before))
    }

    @Test fun `a cancelled train is never offered, nor the cancelled run itself`() {
        val found = Alternatives.find(planner, favorite, listOf(cancelled), day.atTime(6, 30), feed(live("10", 420, 480, cancelled = true)))
        assertNull(found.before)
        assertFalse(listOf(found.before, found.after).any { it?.id == Itinerary(listOf(cancelled)).id })
    }

    @Test fun `the notification says when the trains leave`() {
        val found = Alternatives.find(planner, favorite, listOf(cancelled), day.atTime(6, 50))
        assertEquals("Plus tôt : 07:00 (dans 10 min) · Suivant : 08:10", FavoriteWatchWorker.describe(found, day.atTime(6, 50)))
        assertEquals("07:00 · 08:10", found.times())
    }

    private val follow = FavoriteTrain("12", "TER", "A", "C", a, c, run = day.toString())

    @Test fun `a temporary follow keeps its one run, even after it arrives`() {
        assertEquals(day.atTime(9, 0), planner.nextJourney(follow, day.atTime(6, 0))!!.single().trip.departure)
        assertEquals(day.atTime(9, 0), planner.nextJourney(follow, day.atTime(10, 20))!!.single().trip.departure)
        val candidates = FavoriteRuns.candidates(follow, planner, day.atTime(10, 20))
        assertNull(candidates.late)
        assertTrue(follow.takenOn(day))
        assertFalse(follow.takenOn(day.plusDays(1)))
    }

    @Test fun `a temporary follow goes away 30 minutes after its real arrival`() {
        val noCheck = emptyMap<String, WatchState>()
        assertFalse(TemporaryFollows.isOver(follow, planner, noCheck, day.atTime(10, 29)))
        assertTrue(TemporaryFollows.isOver(follow, planner, noCheck, day.atTime(10, 30)))
        val late = mapOf(follow.key to WatchState("${day}T09:00", "${day}T10:00", arrivalDelayMinutes = 20))
        assertFalse(TemporaryFollows.isOver(follow, planner, late, day.atTime(10, 45)))
        assertTrue(TemporaryFollows.isOver(follow, planner, late, day.atTime(10, 50)))
        val cancelledRun = mapOf(follow.key to WatchState("${day}T09:00", "${day}T10:00", arrivalDelayMinutes = 20, cancelled = true))
        assertTrue(TemporaryFollows.isOver(follow, planner, cancelledRun, day.atTime(10, 30)))
        assertFalse(TemporaryFollows.isOver(favorite, planner, noCheck, day.plusDays(5).atTime(0, 0)))
    }

    @Test fun `a run the timetables no longer have stays for its day`() {
        val gone = follow.copy(number = "99")
        assertFalse(TemporaryFollows.isOver(gone, planner, emptyMap(), day.atTime(23, 0)))
        assertTrue(TemporaryFollows.isOver(gone, planner, emptyMap(), day.plusDays(1).atTime(0, 1)))
    }

    @Test fun `finished follows leave the list, regular favorites stay`() {
        val history = RecentHistory(favorites = listOf(favorite, follow))
        assertEquals(listOf(favorite), history.withoutFinishedFollows(planner, emptyMap(), day.atTime(11, 0)).favorites)
    }

    @Test fun `the follow between the same stations that day replaces the cancelled run`() {
        val favorites = listOf(favorite, follow)
        assertEquals(follow, TemporaryFollows.replacement(favorite, day, favorites))
        assertNull(TemporaryFollows.replacement(favorite, day.plusDays(1), favorites))
        assertNull(TemporaryFollows.replacement(favorite, day, listOf(favorite, follow.copy(toName = "B"))))
    }

    @Test fun `favorites saved before are regular`() {
        val saved = kotlinx.serialization.json.Json.decodeFromString<FavoriteTrain>("""{"number":"11","product":"TER","fromName":"A","toName":"C"}""")
        assertFalse(saved.isTemporary)
        assertTrue(saved.takenOn(LocalDateTime.now().toLocalDate()))
    }
}
