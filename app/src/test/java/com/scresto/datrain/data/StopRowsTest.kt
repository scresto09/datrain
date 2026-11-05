package com.scresto.datrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StopRowsTest {
    private val day = LocalDate.of(2026, 11, 5)
    private val trip = Trip("1", "TER", "C", day, day.atTime(1, 0), day.atTime(3, 0), fromKey = "A", toKey = "C")
    private val names = mapOf("A" to "Alpha", "B" to "Beta", "C" to "Gamma")
    private val stops = listOf(
        LiveStop("A", null, 3600, departureDelaySeconds = 300, platform = "4"),
        LiveStop("B", 7200, 7260),
        LiveStop("C", 10800, null, arrivalDelaySeconds = 30),
    )

    private fun rows(progress: TrainProgress?, list: List<LiveStop> = stops, realtime: Boolean = true) =
        StopRows.build(LiveTrain("1", "1", day, false, realtime, list), names, progress, trip)

    @Test fun `the first stop shows its departure and the others their arrival`() {
        val result = rows(null)
        assertTrue(result[0].isDeparture)
        assertEquals(3600, result[0].planned)
        assertFalse(result[1].isDeparture)
        assertEquals(7200, result[1].planned)
    }

    @Test fun `an estimate appears only when the clock differs`() {
        val result = rows(null)
        assertEquals(3900, result[0].estimated)
        assertNull(result[1].estimated)
        assertNull(result[2].estimated)
    }

    @Test fun `no estimate without real time`() {
        assertNull(rows(null, realtime = false)[0].estimated)
    }

    @Test fun `statuses follow the progress`() {
        assertEquals(
            listOf(StopStatus.NEXT, StopStatus.AHEAD, StopStatus.AHEAD),
            rows(TrainProgress.NotStarted).map { it.status },
        )
        assertEquals(
            listOf(StopStatus.PASSED, StopStatus.NEXT, StopStatus.AHEAD),
            rows(TrainProgress.Between(0, 1, 0.5)).map { it.status },
        )
        assertEquals(
            listOf(StopStatus.PASSED, StopStatus.NEXT, StopStatus.AHEAD),
            rows(TrainProgress.AtStop(1)).map { it.status },
        )
        assertEquals(List(3) { StopStatus.PASSED }, rows(TrainProgress.Arrived).map { it.status })
    }

    @Test fun `a removed stop has no route position and does not shift the others`() {
        val list = listOf(stops[0], stops[1].copy(removed = true), stops[2])
        val result = rows(TrainProgress.AtStop(1), list)
        assertEquals(listOf(0, null, 1), result.map { it.activeIndex })
        assertEquals(listOf(StopStatus.PASSED, StopStatus.REMOVED, StopStatus.NEXT), result.map { it.status })
    }

    @Test fun `marks where the traveller boards and alights`() {
        val result = rows(null)
        assertTrue(result[0].isBoarding)
        assertFalse(result[1].isBoarding || result[1].isAlighting)
        assertTrue(result[2].isAlighting)
    }

    @Test fun `boarding along the route shows the departure time`() {
        val boarding = trip.copy(fromKey = "B")
        val row = StopRows.build(LiveTrain("1", "1", day, false, true, stops), names, null, boarding)[1]
        assertTrue(row.isDeparture)
        assertEquals(7260, row.planned)
    }

    @Test fun `the train position follows its progress`() {
        val rows = rows(null)
        assertNull(StopRows.trainPosition(rows, null))
        assertEquals(0.0, StopRows.trainPosition(rows, TrainProgress.NotStarted)!!, 0.0)
        assertEquals(1.0, StopRows.trainPosition(rows, TrainProgress.AtStop(1))!!, 0.0)
        assertEquals(1.25, StopRows.trainPosition(rows, TrainProgress.Between(1, 2, 0.25))!!, 0.0)
        assertEquals(2.0, StopRows.trainPosition(rows, TrainProgress.Arrived)!!, 0.0)
    }

    @Test fun `the train crosses a removed stop in the list`() {
        val list = listOf(stops[0], stops[1].copy(removed = true), stops[2])
        val rows = rows(null, list)
        assertEquals(1.0, StopRows.trainPosition(rows, TrainProgress.Between(0, 1, 0.5))!!, 0.0)
        assertEquals(2.0, StopRows.trainPosition(rows, TrainProgress.Arrived)!!, 0.0)
    }

    private fun liveTrain(list: List<LiveStop>) = LiveTrain("1", "1", day, false, true, list, destination = "Beta", origin = "Beta")

    @Test fun `a route built before a stop was known must be built again for its name`() {
        val stations = mapOf("A" to StationInfo("Alpha", null), "B" to StationInfo("Beta", null), "C" to StationInfo("Gamma", null))
        val active = listOf(stops[0], stops[1])
        val route = TrainRoute.build(active, stations, emptyList())
        val train = liveTrain(active + stops[2].copy(removed = true))
        assertFalse(route.fits(train))
        assertTrue(TrainRoute.build(train.activeStops, stations, emptyList(), train.stops).fits(train))
    }

    @Test fun `removed last stops make an exceptional terminus`() {
        val train = liveTrain(listOf(stops[0], stops[1], stops[2].copy(removed = true)))
        assertEquals(listOf("Terminus exceptionnel : Beta"), train.serviceNotes())
    }

    @Test fun `removed first or middle stops are told too`() {
        assertEquals(listOf("Origine exceptionnelle : Beta"), liveTrain(listOf(stops[0].copy(removed = true), stops[1], stops[2])).serviceNotes())
        assertEquals(listOf("1 arrêt supprimé sur le parcours"), liveTrain(listOf(stops[0], stops[1].copy(removed = true), stops[2])).serviceNotes())
    }

    @Test fun `a normal or cancelled train has no notes`() {
        assertTrue(liveTrain(stops).serviceNotes().isEmpty())
        assertTrue(liveTrain(listOf(stops[0], stops[1], stops[2].copy(removed = true))).copy(cancelled = true).serviceNotes().isEmpty())
    }

    @Test fun `an unknown station keeps a readable name`() {
        val list = listOf(LiveStop("X", null, 100))
        assertEquals("Gare X", rows(null, list).single().name)
    }

    @Test fun `route names include removed stops`() {
        val stations = mapOf("A" to StationInfo("Alpha", null), "B" to StationInfo("Beta", null), "C" to StationInfo("Gamma", null))
        val active = listOf(stops[0], stops[2])
        val route = TrainRoute.build(active, stations, emptyList(), stops)
        assertEquals("Beta", route.stationNames["B"])
    }
}
