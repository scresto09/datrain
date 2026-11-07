package com.scresto.datrain.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class BoardPatchTest {
    private val day = LocalDate.of(2026, 11, 6)
    private val train = LiveTrain(
        "k", "872012", day, cancelled = false, hasRealtime = true,
        stops = listOf(
            LiveStop("1", null, 28800, departureDelaySeconds = 300),
            LiveStop("2", 30000, 30060, 300, 300),
            LiveStop("3", 32000, null, 600),
        ),
    )

    private fun board(vararg passages: Passage) = Board(day, OffsetDateTime.now().plusMinutes(5), passages.toList())

    @Test fun `the next station's delay moves the following stops by the same change`() {
        val passage = Passage("872012", 30000, 30060, 900, 900, "3", cancelled = false)
        val found = BoardPatch.find(board(passage), train.number, day, 30060, 30000, OffsetDateTime.now())!!
        val patched = BoardPatch.apply(train, 1, found)
        assertEquals(listOf(28800 + 300, 30060 + 900, null), patched.stops.map { it.estimatedDeparture })
        assertEquals(listOf(0, 900, 1200), patched.stops.map { it.arrivalDelaySeconds })
        assertEquals("3", patched.stops[1].platform)
    }

    @Test fun `after midnight the map also reads the next day's board`() {
        val late = Passage("872012", 30000 - 86400, 30060 - 86400, 900, 900, null, cancelled = false)
        val nextDay = Board(day.plusDays(1), OffsetDateTime.now().plusMinutes(5), listOf(late))
        assertEquals(late, BoardPatch.find(nextDay, "872012", day, 30060, 30000, OffsetDateTime.now()))
    }

    @Test fun `another train with the same number at another time is not taken`() {
        val other = Passage("872012", 70000, 70060, 0, 0, null, cancelled = false)
        val mine = Passage("872012", 30000, 30060, 60, 60, null, cancelled = false)
        assertEquals(mine, BoardPatch.find(board(other, mine), train.number, day, 30060, 30000, OffsetDateTime.now()))
    }
}
