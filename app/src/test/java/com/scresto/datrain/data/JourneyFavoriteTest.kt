package com.scresto.datrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JourneyFavoriteTest {
    private val journey = FavoriteTrain("1", "TER", "A", "M", connections = listOf(FavoriteTrain("2", "TER", "M", "C")))

    private fun planner(secondDays: List<Int>): JourneyPlanner {
        val b = TableBuilder()
        val a = b.station("A")
        val m = b.station("M")
        val c = b.station("C")
        b.trip("1", stop(a, 480), stop(m, 540))
        b.trip("2", stop(m, 560), stop(c, 620), runningDays = secondDays)
        return JourneyPlanner(b.build())
    }

    @Test fun `the next run is a day when both trains run`() {
        val p = planner(secondDays = listOf(3))
        val rides = p.nextJourney(journey, p.table.firstDate.plusDays(2).atTime(7, 0))!!
        val day = p.table.firstDate.plusDays(3)
        assertEquals(listOf(day, day), rides.map { it.trip.serviceDate })
    }

    @Test fun `a journey under way stays until its last train arrives`() {
        val p = planner(secondDays = (0 until 7).toList())
        val day = p.table.firstDate.plusDays(2)
        val rides = p.nextJourney(journey, day.atTime(9, 30))!!
        assertEquals(day, rides.first().trip.serviceDate)
    }

    @Test fun `a change at risk is told once`() {
        val before = WatchState("2026-11-05T08:00", "2026-11-05T09:00", delayMinutes = 18)
        val after = WatchState("2026-11-05T09:20", "2026-11-05T10:20")
        assertEquals(2L, FavoriteWatch.connectionAtRisk(before, after, 5))
        assertNull(FavoriteWatch.connectionAtRisk(before, after.copy(connectionWarned = true), 5))
        assertNull(FavoriteWatch.connectionAtRisk(before.copy(delayMinutes = 5), after, 5))
    }
}
