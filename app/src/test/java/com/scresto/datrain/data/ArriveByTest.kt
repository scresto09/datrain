package com.scresto.datrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ArriveByTest {
    private val day = LocalDate.of(2026, 11, 4)

    private fun numbers(found: List<Itinerary>) = found.map { it.rides.single().trip.number }

    @Test fun `keeps only journeys arriving in time, closest first`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        for (hour in 8..12) b.trip("t$hour", stop(a, hour * 60), stop(c, hour * 60 + 30))
        val found = JourneyPlanner(b.build()).searchArrivingBy(a, c, day, 10 * 3600 + 40 * 60)
        assertEquals(listOf("t10", "t9", "t8"), numbers(found))
    }

    @Test fun `arriving exactly on time counts`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        b.trip("t", stop(a, 480), stop(c, 540))
        assertEquals(listOf("t"), numbers(JourneyPlanner(b.build()).searchArrivingBy(a, c, day, 540 * 60)))
    }

    @Test fun `a faster journey arriving too late does not hide one in time`() {
        val b = TableBuilder()
        val a = b.station("A")
        val m = b.station("M")
        val c = b.station("C")
        b.trip("late", stop(a, 600), stop(c, 700))
        b.trip("one", stop(a, 480), stop(m, 500))
        b.trip("two", stop(m, 515), stop(c, 650))
        val found = JourneyPlanner(b.build()).searchArrivingBy(a, c, day, 660 * 60)
        assertEquals(listOf(listOf("one", "two")), found.map { it.rides.map { r -> r.trip.number } })
    }

    @Test fun `finds a long journey leaving hours before`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        b.trip("long", stop(a, 120), stop(c, 900))
        assertEquals(listOf("long"), numbers(JourneyPlanner(b.build()).searchArrivingBy(a, c, day, 16 * 3600)))
    }

    @Test fun `includes a night train leaving the evening before`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        b.trip("night", stop(a, 22 * 60), stop(c, 30 * 60))
        val only = JourneyPlanner(b.build()).searchArrivingBy(a, c, day, 7 * 3600).single()
        assertEquals(LocalDateTime.of(2026, 11, 3, 22, 0), only.departure)
        assertEquals(LocalDateTime.of(2026, 11, 4, 6, 0), only.arrival)
    }

    @Test fun `a train arriving the day before is not offered`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        b.trip("yesterday", stop(a, 17 * 60), stop(c, 21 * 60), runningDays = listOf(1))
        assertTrue(JourneyPlanner(b.build()).searchArrivingBy(a, c, day, 18 * 3600).isEmpty())
    }

    @Test fun `a frequent line still returns the latest departures`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        for (minute in 300 until 1200 step 5) b.trip("t$minute", stop(a, minute), stop(c, minute + 20))
        val found = JourneyPlanner(b.build()).searchArrivingBy(a, c, day, 1000 * 60)
        assertEquals("t980", found.first().rides.single().trip.number)
        assertEquals(12, found.size)
    }

    private fun numbersNetwork(): Pair<JourneyPlanner, Int> {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        b.trip("872640", stop(a, 480), stop(c, 540), runningDays = listOf(2))
        b.trip("87264", stop(a, 600), stop(c, 660), runningDays = listOf(2))
        b.trip("UZAR87", stop(a, 700), stop(c, 760), runningDays = listOf(2))
        b.trip("142174-142175", stop(a, 800), stop(c, 860), runningDays = listOf(2))
        b.trip("872641", stop(a, 900), stop(c, 960), runningDays = listOf(3))
        return JourneyPlanner(b.build()) to a
    }

    @Test fun `finds a train by its number on the right day`() {
        val (planner, _) = numbersNetwork()
        val only = planner.searchTrainNumber("872640", day).single()
        assertEquals("A", only.fromName)
        assertEquals("C", only.toName)
        assertEquals(LocalDateTime.of(2026, 11, 4, 8, 0), only.trip.departure)
        assertEquals(LocalDateTime.of(2026, 11, 4, 9, 0), only.trip.arrival)
        assertEquals("", only.trip.toKey)
        assertEquals(2, only.trip.plannedStops.size)
    }

    @Test fun `the exact number comes before longer ones`() {
        val (planner, _) = numbersNetwork()
        assertEquals(listOf("87264", "872640"), planner.searchTrainNumber("87264", day).map { it.trip.number })
    }

    @Test fun `finds a mission code in any case and either part of a double number`() {
        val (planner, _) = numbersNetwork()
        assertEquals(listOf("UZAR87"), planner.searchTrainNumber("uzar", day).map { it.trip.number })
        assertEquals(listOf("142174-142175"), planner.searchTrainNumber("142175", day).map { it.trip.number })
    }

    @Test fun `a number on another day or an empty search finds nothing`() {
        val (planner, _) = numbersNetwork()
        assertTrue(planner.searchTrainNumber("872641", day).isEmpty())
        assertTrue(planner.searchTrainNumber("  ", day).isEmpty())
    }

    private fun favoriteNetwork(): JourneyPlanner {
        val b = TableBuilder()
        val a = b.station("A", uic = 87000001)
        val m = b.station("M", uic = 87000003)
        val c = b.station("C", uic = 87000002)
        b.trip("7", stop(a, 480), stop(m, 500, 505), stop(c, 540), runningDays = listOf(2, 4))
        b.trip("8", stop(c, 600), stop(a, 660), runningDays = listOf(2))
        return JourneyPlanner(b.build())
    }

    private fun favorite(number: String = "7", from: String = "M", to: String = "C") =
        FavoriteTrain(number, "TER", from, to)

    @Test fun `a favorite gives its run of the day between the user's stations`() {
        val ride = favoriteNetwork().nextRun(favorite(), day.atTime(7, 0))!!
        assertEquals(LocalDateTime.of(2026, 11, 4, 8, 25), ride.trip.departure)
        assertEquals(LocalDateTime.of(2026, 11, 4, 9, 0), ride.trip.arrival)
        assertEquals("M", ride.fromName)
    }

    @Test fun `a favorite under way stays until it arrives`() {
        val ride = favoriteNetwork().nextRun(favorite(), day.atTime(8, 50))!!
        assertEquals(day, ride.trip.serviceDate)
    }

    @Test fun `once arrived a favorite moves to its next day`() {
        val ride = favoriteNetwork().nextRun(favorite(), day.atTime(9, 1))!!
        assertEquals(day.plusDays(2), ride.trip.serviceDate)
    }

    @Test fun `a favorite with no more run or wrong direction has none`() {
        val planner = favoriteNetwork()
        assertTrue(planner.nextRun(favorite("99"), day.atTime(7, 0)) == null)
        assertTrue(planner.nextRun(favorite(from = "C", to = "A"), day.atTime(7, 0)) == null)
        assertTrue(planner.nextRun(favorite(from = "Z"), day.atTime(7, 0)) == null)
        assertTrue(planner.nextRun(favorite("8", "C", "A"), day.plusDays(1).atTime(7, 0)) == null)
    }

    @Test fun `nothing arriving in time gives no result`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        b.trip("t", stop(a, 600), stop(c, 700), runningDays = listOf(2))
        assertTrue(JourneyPlanner(b.build()).searchArrivingBy(a, c, day, 300 * 60).isEmpty())
    }
}
