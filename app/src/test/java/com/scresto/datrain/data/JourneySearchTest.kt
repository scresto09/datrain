package com.scresto.datrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class JourneySearchTest {
    private val day = LocalDate.of(2026, 11, 4)
    private val dayIndex = 2

    private fun search(table: RailTimetable, from: Int, to: Int, startMinutes: Int = 0, date: LocalDate = day) =
        JourneySearch(table, date).search(from, to, startMinutes * 60)

    private fun numbers(table: RailTimetable, journey: Journey) =
        journey.rides.map { table.strings[table.tripNumber[it.trip]] }

    @Test fun `finds a direct train`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        b.trip("1", stop(a, 480), stop(c, 540))
        val table = b.build()
        val only = search(table, a, c).single()
        assertEquals(480 * 60, only.departure)
        assertEquals(540 * 60, only.arrival)
        assertEquals(0, only.changes)
    }

    @Test fun `connects two trains when the change time is respected`() {
        val b = TableBuilder()
        val a = b.station("A")
        val m = b.station("M")
        val c = b.station("C")
        b.trip("1", stop(a, 480), stop(m, 540))
        b.trip("2", stop(m, 550), stop(c, 600))
        val table = b.build()
        val only = search(table, a, c).single()
        assertEquals(listOf("1", "2"), numbers(table, only))
    }

    @Test fun `refuses a connection shorter than the change time`() {
        val b = TableBuilder()
        val a = b.station("A")
        val m = b.station("M")
        val c = b.station("C")
        b.trip("1", stop(a, 480), stop(m, 540))
        b.trip("2", stop(m, 545), stop(c, 600))
        assertTrue(search(b.build(), a, c).isEmpty())
    }

    @Test fun `changing station on foot uses the walking time`() {
        val b = TableBuilder()
        val a = b.station("A")
        val m = b.station("M")
        val n = b.station("N")
        val c = b.station("C")
        b.walk(m, n, 900)
        b.trip("1", stop(a, 480), stop(m, 540))
        b.trip("2", stop(n, 550), stop(c, 600))
        b.trip("3", stop(n, 556), stop(c, 610))
        val table = b.build()
        val only = search(table, a, c).single()
        assertEquals(listOf("1", "3"), numbers(table, only))
        assertEquals(3, only.legs.size)
        assertEquals(-1, only.legs[1].trip)
    }

    @Test fun `does not use a train on a day it does not run`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        b.trip("1", stop(a, 480), stop(c, 540), runningDays = listOf(dayIndex + 1))
        assertTrue(search(b.build(), a, c).isEmpty())
    }

    @Test fun `does not board where boarding is forbidden nor alight where it is forbidden`() {
        val b = TableBuilder()
        val a = b.station("A")
        val m = b.station("M")
        val c = b.station("C")
        b.trip("1", stop(a, 480), stop(m, 500), stop(c, 540), flags = listOf(3, 1, 3))
        val table = b.build()
        assertTrue(search(table, a, m).isEmpty())
        assertEquals(1, search(table, a, c).size)
        val other = TableBuilder()
        val x = other.station("X")
        val y = other.station("Y")
        val z = other.station("Z")
        other.trip("2", stop(x, 480), stop(y, 500), stop(z, 540), flags = listOf(3, 2, 3))
        assertTrue(search(other.build(), y, z).isEmpty())
    }

    @Test fun `finds an arrival after midnight`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        b.trip("1", stop(a, 23 * 60), stop(c, 25 * 60))
        val only = search(b.build(), a, c).single()
        assertEquals(25 * 3600, only.arrival)
    }

    @Test fun `a train started the day before can be taken after midnight`() {
        val b = TableBuilder()
        val a = b.station("A")
        val m = b.station("M")
        val c = b.station("C")
        b.trip("1", stop(a, 23 * 60), stop(m, 25 * 60, 25 * 60 + 5), stop(c, 28 * 60), runningDays = listOf(dayIndex - 1))
        val only = search(b.build(), m, c).single()
        assertEquals(65 * 60, only.departure)
        assertEquals(240 * 60, only.arrival)
        assertEquals(-1, only.rides.single().dayOffset)
    }

    @Test fun `keeps a slower journey with fewer changes when the gain is small only if worthwhile`() {
        val b = TableBuilder()
        val a = b.station("A")
        val m = b.station("M")
        val c = b.station("C")
        b.trip("direct", stop(a, 480), stop(c, 660))
        b.trip("1", stop(a, 480), stop(m, 500))
        b.trip("2", stop(m, 515), stop(c, 650))
        val slightGain = b.build()
        assertEquals(listOf(listOf("direct")), search(slightGain, a, c).map { numbers(slightGain, it) })

        val big = TableBuilder()
        val x = big.station("A")
        val y = big.station("M")
        val z = big.station("C")
        big.trip("direct", stop(x, 480), stop(z, 720))
        big.trip("1", stop(x, 480), stop(y, 500))
        big.trip("2", stop(y, 515), stop(z, 650))
        val bigGain = big.build()
        assertEquals(2, search(bigGain, x, z).size)
    }

    @Test fun `a slower direct train leaving early is not lost to a faster connection`() {
        val b = TableBuilder()
        val a = b.station("A")
        val m = b.station("M")
        val c = b.station("C")
        b.trip("direct", stop(a, 480), stop(c, 800))
        b.trip("1", stop(a, 480), stop(m, 500))
        b.trip("2", stop(m, 515), stop(c, 600))
        val table = b.build()
        assertTrue(search(table, a, c).any { numbers(table, it) == listOf("direct") })
    }

    @Test fun `drops a journey that leaves earlier and arrives at the same time`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        b.trip("slow", stop(a, 480), stop(c, 600))
        b.trip("fast", stop(a, 530), stop(c, 600))
        val table = b.build()
        assertEquals(listOf(listOf("fast")), search(table, a, c).map { numbers(table, it) })
    }

    @Test fun `lists several departures of the day`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        for (hour in 8..12) b.trip("t$hour", stop(a, hour * 60), stop(c, hour * 60 + 30))
        assertEquals(5, search(b.build(), a, c).size)
    }

    @Test fun `ignores departures before the requested time`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        b.trip("early", stop(a, 480), stop(c, 540))
        b.trip("late", stop(a, 600), stop(c, 660))
        val table = b.build()
        assertEquals(listOf(listOf("late")), search(table, a, c, startMinutes = 500).map { numbers(table, it) })
    }

    @Test fun `does not ride back through the origin`() {
        val b = TableBuilder()
        val a = b.station("A")
        val m = b.station("M")
        val c = b.station("C")
        b.trip("out", stop(a, 480), stop(m, 490))
        b.trip("back", stop(m, 505), stop(a, 515))
        b.trip("next", stop(a, 530), stop(c, 600))
        val table = b.build()
        assertEquals(listOf(listOf("next")), search(table, a, c).map { numbers(table, it) })
    }

    @Test fun `no route gives no result`() {
        val b = TableBuilder()
        val a = b.station("A")
        val c = b.station("C")
        val d = b.station("D")
        b.trip("1", stop(a, 480), stop(c, 540))
        assertTrue(search(b.build(), a, d).isEmpty())
    }
}
