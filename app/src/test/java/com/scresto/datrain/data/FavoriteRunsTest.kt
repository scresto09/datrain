package com.scresto.datrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.OffsetDateTime

class FavoriteRunsTest {
    private val b = TableBuilder().apply {
        val a = station("A", uic = 87000001)
        val c = station("C", uic = 87000002)
        trip("1", stop(a, 480), stop(c, 540))
    }
    private val planner = JourneyPlanner(b.build())
    private val favorite = FavoriteTrain("1", "TER", "A", "C", 87000001, 87000002)
    private val day = planner.table.firstDate.plusDays(2)
    private val now: LocalDateTime = day.atTime(9, 30)
    private val candidates = FavoriteRuns.candidates(favorite, planner, now)

    private fun feed(arrivalDelay: Int) = LiveFeed(
        OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(5),
        listOf(LiveTrain("1", "1", day, false, true, listOf(LiveStop("87000001", null, 28800), LiveStop("87000002", 32400, null, arrivalDelay)))),
    )

    @Test fun `after its planned arrival the run of the day is a late candidate`() {
        assertEquals(day, candidates.late!!.single().trip.serviceDate)
        assertEquals(day.plusDays(1), candidates.next!!.single().trip.serviceDate)
    }

    @Test fun `still going in the feed, the late run is followed`() {
        assertEquals(candidates.late, FavoriteRuns.choose(candidates, feed(3600), null, now))
    }

    @Test fun `arrived in the feed, the next run is followed`() {
        assertEquals(candidates.next, FavoriteRuns.choose(candidates, feed(600), null, now))
    }

    @Test fun `without the feed the saved state of that run decides`() {
        val late = WatchState(day.atTime(8, 0).toString(), day.atTime(9, 0).toString(), delayMinutes = 60)
        assertEquals(candidates.late, FavoriteRuns.choose(candidates, null, late, now))
        assertEquals(candidates.next, FavoriteRuns.choose(candidates, null, late.copy(delayMinutes = 20), now))
        assertEquals(candidates.next, FavoriteRuns.choose(candidates, null, null, now))
    }

    @Test fun `before the planned arrival there is no late candidate`() {
        assertNull(FavoriteRuns.candidates(favorite, planner, day.atTime(8, 30)).late)
    }
}
