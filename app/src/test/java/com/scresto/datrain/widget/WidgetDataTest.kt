package com.scresto.datrain.widget

import com.scresto.datrain.data.FavoriteTrain
import com.scresto.datrain.data.RideLeg
import com.scresto.datrain.data.Trip
import com.scresto.datrain.data.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WidgetDataTest {
    private val day = LocalDate.of(2026, 11, 6)
    private val favorite = FavoriteTrain("872704", "TER", "Carbonne", "Saint-Agne")
    private val ride = RideLeg(
        Trip("872704", "TER", "Toulouse", day, day.atTime(7, 14), day.atTime(7, 45)),
        "Carbonne", "Saint-Agne", isBus = false,
    )
    private val state = WatchState("2026-11-06T07:14", "2026-11-06T07:45")
    private val now: LocalDateTime = day.atTime(6, 30)

    @Test fun `a delay shows the estimated times`() {
        val next = WidgetData.next(favorite, listOf(ride), listOf(state.copy(delayMinutes = 15)), now)
        assertEquals(WidgetStatus.DELAYED, next.status)
        assertEquals("07:29", next.newDeparture)
        assertEquals("Carbonne → Saint-Agne 08:00", next.route)
        assertEquals("TER 872704 · aujourd'hui", next.title)
    }

    @Test fun `far from the departure nothing is claimed`() {
        val next = WidgetData.next(favorite, listOf(ride), listOf(null), day.minusDays(1).atTime(20, 0))
        assertEquals(WidgetStatus.PLANNED, next.status)
        assertNull(next.newDeparture)
    }

    @Test fun `a cancellation wins over a delay`() {
        val next = WidgetData.next(favorite, listOf(ride), listOf(state.copy(cancelled = true, delayMinutes = 10)), now)
        assertEquals(WidgetStatus.CANCELLED, next.status)
    }
}
