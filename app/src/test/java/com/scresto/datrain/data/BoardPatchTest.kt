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
        val found = BoardPatch.find(board(passage), train, train.stops[1])!!
        val patched = BoardPatch.apply(train, 1, found)
        assertEquals(listOf(28800 + 300, 30060 + 900, null), patched.stops.map { it.estimatedDeparture })
        assertEquals(listOf(0, 900, 1200), patched.stops.map { it.arrivalDelaySeconds })
        assertEquals("3", patched.stops[1].platform)
    }

    @Test fun `another train with the same number at another time is not taken`() {
        val other = Passage("872012", 70000, 70060, 0, 0, null, cancelled = false)
        val mine = Passage("872012", 30000, 30060, 60, 60, null, cancelled = false)
        assertEquals(mine, BoardPatch.find(board(other, mine), train, train.stops[1]))
    }
}
