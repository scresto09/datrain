package com.scresto.datrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

class FavoriteWatchTest {
    private val day = LocalDate.of(2026, 11, 5)
    private val state = WatchState("2026-11-05T15:00", "2026-11-05T16:00")
    private fun at(hour: Int, minute: Int = 0): LocalDateTime = day.atTime(hour, minute)

    private val ride = RideLeg(
        Trip("42", "TER", "C", day, at(15), at(16), fromKey = "87000001", toKey = "87000002"),
        "A", "B", isBus = false,
    )

    @Test fun `no network far from the departure, the full feed hourly, then station boards`() {
        assertEquals(LiveCheck.NONE, FavoriteWatch.liveCheck(state, at(8)))
        assertEquals(LiveCheck.FULL_FEED, FavoriteWatch.liveCheck(state, at(9)))
        assertEquals(LiveCheck.NONE, FavoriteWatch.liveCheck(state.copy(fullAt = "2026-11-05T09:00"), at(9, 45)))
        assertEquals(LiveCheck.FULL_FEED, FavoriteWatch.liveCheck(state.copy(fullAt = "2026-11-05T09:00"), at(10)))
        assertEquals(LiveCheck.STATION_BOARD, FavoriteWatch.liveCheck(state.copy(fullAt = "2026-11-05T11:30"), at(12)))
        assertEquals(LiveCheck.STATION_BOARD, FavoriteWatch.liveCheck(state, at(15, 30)))
    }

    @Test fun `no check once arrived or cancelled, a delay extends the watch`() {
        assertEquals(LiveCheck.NONE, FavoriteWatch.liveCheck(state, at(16, 1)))
        assertEquals(LiveCheck.STATION_BOARD, FavoriteWatch.liveCheck(state.copy(delayMinutes = 30), at(16, 1)))
        assertEquals(LiveCheck.NONE, FavoriteWatch.liveCheck(state.copy(cancelled = true), at(14)))
    }

    @Test fun `alerts are read once a day, within two days of the run`() {
        assertFalse(FavoriteWatch.needsAlerts(state, LocalDateTime.of(2026, 11, 3, 14, 0)))
        assertTrue(FavoriteWatch.needsAlerts(state, LocalDateTime.of(2026, 11, 3, 16, 0)))
        assertFalse(FavoriteWatch.needsAlerts(state.copy(alertsDay = "2026-11-05"), at(9)))
    }

    @Test fun `close to the departure, alerts are read every half hour until arrival`() {
        val read = state.copy(alertsDay = "2026-11-05", alertsAt = "2026-11-05T12:10")
        assertFalse(FavoriteWatch.needsAlerts(read, at(12, 30)))
        assertTrue(FavoriteWatch.needsAlerts(read, at(12, 40)))
        assertFalse(FavoriteWatch.needsAlerts(read.copy(alertsAt = "2026-11-05T15:30"), at(16, 5)))
    }

    @Test fun `the first look at a favorite only tells a cancellation`() {
        assertTrue(FavoriteWatch.changes(null, state.copy(delayMinutes = 20), at(9)).isEmpty())
        assertEquals(listOf(FavoriteChange.Cancelled), FavoriteWatch.changes(null, state.copy(cancelled = true), at(9)))
    }

    @Test fun `going back to an earlier run still running late announces nothing`() {
        val tomorrow = WatchState("2026-11-06T15:00", "2026-11-06T16:00")
        assertTrue(FavoriteWatch.changes(tomorrow, state, at(17)).isEmpty())
    }

    @Test fun `a run gone from the timetables is announced`() {
        val next = WatchState("2026-11-06T15:00", "2026-11-06T16:00")
        assertEquals(listOf(FavoriteChange.Removed(at(15))), FavoriteWatch.changes(state, next, at(9)))
    }

    @Test fun `moving to the next run after arrival says nothing`() {
        val next = WatchState("2026-11-06T15:00", "2026-11-06T16:00")
        assertTrue(FavoriteWatch.changes(state, next, at(17)).isEmpty())
    }

    @Test fun `a cancellation is announced once`() {
        val cancelled = state.copy(cancelled = true)
        assertEquals(listOf(FavoriteChange.Cancelled), FavoriteWatch.changes(state, cancelled, at(9)))
        assertTrue(FavoriteWatch.changes(cancelled, cancelled, at(9)).isEmpty())
    }

    @Test fun `only a delay change of five minutes or more is announced`() {
        assertTrue(FavoriteWatch.changes(state, state.copy(delayMinutes = 4), at(14)).isEmpty())
        assertEquals(listOf(FavoriteChange.Delayed(5)), FavoriteWatch.changes(state, state.copy(delayMinutes = 5), at(14)))
        assertEquals(listOf(FavoriteChange.Delayed(0)), FavoriteWatch.changes(state.copy(notifiedDelay = 20, delayMinutes = 20), state, at(14)))
    }

    @Test fun `small delay changes add up, and a told delay is not repeated`() {
        val creeping = state.copy(delayMinutes = 3)
        assertTrue(FavoriteWatch.changes(state, creeping, at(14)).isEmpty())
        val later = creeping.copy(delayMinutes = 6)
        val changes = FavoriteWatch.changes(FavoriteWatch.remembered(state, creeping, emptyList()), later, at(14))
        assertEquals(listOf(FavoriteChange.Delayed(6)), changes)
        val told = FavoriteWatch.remembered(creeping, later, changes)
        assertEquals(6, told.notifiedDelay)
        assertTrue(FavoriteWatch.changes(told, told.copy(delayMinutes = 7), at(14)).isEmpty())
    }

    @Test fun `a delay seen on the first look is not announced later`() {
        val first = state.copy(delayMinutes = 30)
        val remembered = FavoriteWatch.remembered(null, first, emptyList())
        assertTrue(FavoriteWatch.changes(remembered, first, at(14)).isEmpty())
    }

    @Test fun `a platform change is announced, not the first platform known`() {
        assertTrue(FavoriteWatch.changes(state, state.copy(platform = "4"), at(14)).isEmpty())
        assertEquals(
            listOf(FavoriteChange.PlatformChanged("5")),
            FavoriteWatch.changes(state.copy(platform = "4"), state.copy(platform = "5"), at(14)),
        )
    }

    @Test fun `only new messages are announced`() {
        val old = state.copy(alerts = setOf("Travaux"))
        val new = state.copy(alerts = setOf("Travaux", "Grève"))
        assertEquals(listOf(FavoriteChange.NewAlert("Grève")), FavoriteWatch.changes(old, new, at(9)))
    }

    @Test fun `a new timetable time is announced`() {
        val shifted = state.copy(departure = "2026-11-05T15:01")
        assertEquals(listOf(FavoriteChange.ScheduleChanged(at(15, 1))), FavoriteWatch.changes(state, shifted, at(9)))
    }

    @Test fun `known real time stays with the same run, not the next`() {
        val known = state.copy(delayMinutes = 12, platform = "4", fullChecked = true)
        assertEquals(12, FavoriteWatch.stateFor(ride, known).delayMinutes)
        val tomorrow = ride.copy(trip = ride.trip.copy(departure = at(15).plusDays(1), arrival = at(16).plusDays(1)))
        assertEquals(WatchState("2026-11-06T15:00", "2026-11-06T16:00"), FavoriteWatch.stateFor(tomorrow, known))
    }

    private fun board(vararg passages: Passage, stale: OffsetDateTime = OffsetDateTime.now(PARIS).plusHours(1)) =
        Board(day, stale, passages.toList())

    private fun passage(number: String = "42", departure: Int? = 54000, arrival: Int? = 57600, delay: Int = 600, cancelled: Boolean = false) =
        Passage(number, arrival, departure, delay, delay, "4", cancelled)

    private val now = OffsetDateTime.now(PARIS)

    @Test fun `a station board gives delay, platform and cancellation`() {
        val updated = FavoriteWatch.withBoard(state, board(passage()), ride, boarding = true, now)!!
        assertEquals(10, updated.delayMinutes)
        assertEquals("4", updated.platform)
        assertTrue(FavoriteWatch.withBoard(state, board(passage(cancelled = true)), ride, true, now)!!.cancelled)
    }

    @Test fun `a station board without the train or out of date cannot tell`() {
        assertNull(FavoriteWatch.withBoard(state, board(passage("7")), ride, true, now))
        val stale = board(passage(), stale = OffsetDateTime.now(PARIS).minusHours(1))
        assertNull(FavoriteWatch.withBoard(state, stale, ride, true, now))
    }

    @Test fun `after midnight the next day's board still has the train`() {
        val nextDay = Board(day.plusDays(1), OffsetDateTime.now(PARIS).plusMinutes(5), listOf(passage(arrival = 57600 - 86400, delay = 420)))
        assertEquals(7, FavoriteWatch.withBoard(state, nextDay, ride, boarding = false, now)!!.delayMinutes)
    }

    @Test fun `among two runs of a number the board picks the right time`() {
        val other = passage(departure = 10000, delay = 1200)
        val updated = FavoriteWatch.withBoard(state, board(other, passage()), ride, true, now)!!
        assertEquals(10, updated.delayMinutes)
    }

    @Test fun `the full feed gives the arrival delay and marks the check done`() {
        val train = LiveTrain(
            "42", "42", day, false, true,
            listOf(
                LiveStop("87000001", null, 54000, departureDelaySeconds = 300, platform = "2"),
                LiveStop("87000002", 57600, null, arrivalDelaySeconds = 900),
            ),
        )
        val feed = LiveFeed(now, now.plusHours(1), listOf(train))
        val updated = FavoriteWatch.withFeed(state, feed, ride)
        assertEquals(15, updated.delayMinutes)
        assertEquals("2", updated.platform)
        assertTrue(updated.fullChecked)
    }

    @Test fun `a board is read from its json`() {
        val body = """{"jour":"2026-11-05","perime_apres":"2026-11-05T17:00:00+01:00","passages":[{"n":"42/2","a":100,"d":200,"ra":60,"rd":120,"v":"3","x":1}]}"""
        val parsed = LiveParser.parseBoard(200, body)
        assertEquals(day, parsed.day)
        val only = parsed.passages.single()
        assertEquals("42", only.number)
        assertEquals(120, only.departureDelaySeconds)
        assertTrue(only.cancelled)
    }

    @Test fun `favorites keep their settings through the history`() {
        val favorite = FavoriteTrain("42", "TER", "A", "B")
        val history = RecentHistory().addingFavorite(favorite).withFavoriteNotify(favorite, false)
        assertFalse(history.favorites.single().notify)
        assertTrue(history.notifications)
    }
}
