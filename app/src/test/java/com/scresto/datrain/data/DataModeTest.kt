package com.scresto.datrain.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

/** What each data mode allows, on Wi-Fi and on mobile data. */
class DataModeTest {
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-11-17T12:00:00+01:00")

    @Test fun `on Wi-Fi every mode is as reactive`() {
        for (mode in DataMode.entries) {
            val policy = DataPolicy(mode, unmetered = true)
            assertTrue(policy.generous)
            assertEquals(Duration.ofMinutes(30), policy.timetableCheckEvery(pending = false))
            assertTrue(policy.mayDownloadTimetables(now, now))
        }
    }

    @Test fun `on mobile data the reactive mode changes nothing`() {
        val policy = DataPolicy(DataMode.REACTIVE, unmetered = false)
        assertTrue(policy.generous)
        assertTrue(policy.mayDownloadTimetables(now, now))
    }

    @Test fun `on mobile data the sparing modes read the timetables every 6 hours, soon when newer ones wait`() {
        for (mode in listOf(DataMode.BALANCED, DataMode.SAVER)) {
            val policy = DataPolicy(mode, unmetered = false)
            assertFalse(policy.generous)
            assertEquals(Duration.ofHours(6), policy.timetableCheckEvery(pending = false))
            assertEquals(Duration.ofMinutes(30), policy.timetableCheckEvery(pending = true))
        }
    }

    @Test fun `balanced downloads on mobile data 6 hours after publication, saver after 24`() {
        val balanced = DataPolicy(DataMode.BALANCED, unmetered = false)
        assertFalse(balanced.mayDownloadTimetables(now.minusHours(5), now))
        assertTrue(balanced.mayDownloadTimetables(now.minusHours(6), now))
        val saver = DataPolicy(DataMode.SAVER, unmetered = false)
        assertFalse(saver.mayDownloadTimetables(now.minusHours(23), now))
        assertTrue(saver.mayDownloadTimetables(now.minusHours(24), now))
    }

    private val day = LocalDate.of(2026, 11, 17)
    private val run = WatchState("${day}T17:40", "${day}T19:40")

    @Test fun `sparing, the full feed is read once per run before the boards take over`() {
        val read = run.copy(fullAt = "${day}T12:00")
        assertEquals(LiveCheck.FULL_FEED, FavoriteWatch.liveCheck(run, day.atTime(12, 0), generous = false))
        assertEquals(LiveCheck.NONE, FavoriteWatch.liveCheck(read, day.atTime(13, 30), generous = false))
        assertEquals(LiveCheck.FULL_FEED, FavoriteWatch.liveCheck(read, day.atTime(13, 30)))
        // Close to the departure, the light boards as always.
        assertEquals(LiveCheck.STATION_BOARD, FavoriteWatch.liveCheck(read, day.atTime(15, 0), generous = false))
    }

    @Test fun `sparing, messages are read every 6 hours, close to the departure too`() {
        val morning = run.copy(alertsAt = "${day}T09:00", alertsDay = "$day")
        assertFalse(FavoriteWatch.needsAlerts(morning, day.atTime(14, 0), generous = false))
        assertTrue(FavoriteWatch.needsAlerts(morning, day.atTime(15, 0), generous = false))
        assertTrue(FavoriteWatch.needsAlerts(morning, day.atTime(10, 0)))
        val close = run.copy(alertsAt = "${day}T15:00", alertsDay = "$day")
        assertFalse(FavoriteWatch.needsAlerts(close, day.atTime(16, 0), generous = false))
        assertTrue(FavoriteWatch.needsAlerts(close, day.atTime(16, 0)))
    }

    @Test fun `history saved before has no mode chosen yet, and the presentation still to see`() {
        val saved = Json { ignoreUnknownKeys = true }.decodeFromString<RecentHistory>("{}")
        assertNull(saved.dataMode)
        assertFalse(saved.introSeen)
        assertTrue(saved.tipsSeen.isEmpty())
        assertEquals(setOf("star", "days"), saved.withTipSeen("star").withTipSeen("days").withTipSeen("star").tipsSeen)
    }
}
