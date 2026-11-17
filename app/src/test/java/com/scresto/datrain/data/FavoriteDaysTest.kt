package com.scresto.datrain.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime

/** A favorite taken on some days only: its other runs are left out everywhere. */
class FavoriteDaysTest {
    private val a = 87000001
    private val c = 87000002
    // 2026-11-02 is a Monday; 14 days.
    private val monday: LocalDate = LocalDate.of(2026, 11, 2)
    private val weekdays = (0 until 14).filter { monday.plusDays(it.toLong()).dayOfWeek.value <= 5 }
    private val planner = JourneyPlanner(
        TableBuilder(days = 14).apply {
            val sa = station("A", uic = a)
            val sc = station("C", uic = c)
            trip("872630", stop(sa, 430), stop(sc, 465), runningDays = weekdays)
            trip("999", stop(sa, 1410), stop(sc, 1470))
        }.build(),
    )
    private val mondayTuesday = FavoriteTrain("872630", "TER", "A", "C", a, c, days = listOf(1, 2))

    @Test fun `the next run skips the days not taken`() {
        val wednesday = monday.plusDays(2)
        val run = planner.nextJourney(mondayTuesday, wednesday.atTime(6, 0))!!
        assertEquals(monday.plusDays(7).atTime(7, 10), run.single().trip.departure)
        assertEquals(monday.plusDays(1).atTime(7, 10), planner.nextJourney(mondayTuesday, monday.atTime(8, 0))!!.single().trip.departure)
    }

    @Test fun `every day is the default`() {
        val all = mondayTuesday.copy(days = emptyList())
        assertEquals(monday.plusDays(2).atTime(7, 10), planner.nextJourney(all, monday.plusDays(2).atTime(6, 0))!!.single().trip.departure)
    }

    @Test fun `an overnight run of a day not taken is not shown again after midnight`() {
        val sundayOnly = FavoriteTrain("999", "TER", "A", "C", a, c, days = listOf(7))
        val run = planner.nextJourney(sundayOnly, monday.atTime(12, 0))!!
        assertEquals(DayOfWeek.SUNDAY, run.single().trip.departure.dayOfWeek)
    }

    @Test fun `no run on the days taken within two weeks gives none`() {
        assertNull(planner.nextJourney(mondayTuesday.copy(days = listOf(6)), monday.atTime(6, 0)))
    }

    @Test fun `the days a train runs are known`() {
        assertEquals(DayOfWeek.entries.take(5).toSet(), planner.runningDays(mondayTuesday))
    }

    @Test fun `a cancelled run on a day not taken is not put back`() {
        val wednesday = monday.plusDays(2)
        val cancelled = LiveTrain(
            "872630", "872630", wednesday, cancelled = true, hasRealtime = true,
            stops = listOf(LiveStop("$a", null, 430 * 60), LiveStop("$c", 465 * 60, null)),
        )
        val feed = LiveFeed(OffsetDateTime.now(), OffsetDateTime.now().plusHours(1), listOf(cancelled))
        assertNull(FavoriteWatch.missingRun(mondayTuesday, null, feed, wednesday.atTime(6, 0)))
        assertEquals(wednesday.atTime(7, 10), FavoriteWatch.missingRun(mondayTuesday.copy(days = emptyList()), null, feed, wednesday.atTime(6, 0))!!.trip.departure)
    }

    private object NoLive : LiveSource {
        override suspend fun feed() = LiveFeed(OffsetDateTime.now(), OffsetDateTime.now().plusHours(1), emptyList())
        override suspend fun board(uic: Int) = Board(LocalDate.MIN, OffsetDateTime.now().plusHours(1), emptyList())
        override suspend fun alerts() = emptyList<ServiceAlert>()
        override suspend fun tripUpdates() = emptyList<RealtimeTrip>()
    }

    @Test fun `unticking the day of the run followed does not announce it cancelled`() {
        val wednesday = monday.plusDays(2)
        val followed = mapOf(mondayTuesday.key to WatchState("${wednesday}T07:10", "${wednesday}T07:45"))
        val result = runBlocking {
            FavoriteCheck.run(listOf(mondayTuesday), { true }, planner, followed, emptySet(), NoLive, wednesday.atTime(6, 0))
        }
        assertTrue(result.notices.isEmpty())
        assertEquals("${monday.plusDays(7)}T07:10", result.states.getValue(mondayTuesday.key).departure)
    }

    @Test fun `favorites saved before keep every day`() {
        val saved = """{"number":"872630","product":"TER","fromName":"A","toName":"C"}"""
        assertEquals(emptyList<Int>(), Json { ignoreUnknownKeys = true }.decodeFromString<FavoriteTrain>(saved).days)
    }
}

/** A journey with a change: its days are those of the first departure, for every train of it. */
class JourneyDaysTest {
    private val a = 87000001
    private val b = 87000002
    private val c = 87000003
    private val monday: LocalDate = LocalDate.of(2026, 11, 2)
    private val weekdays = (0 until 14).filter { monday.plusDays(it.toLong()).dayOfWeek.value <= 5 }
    private val notWednesday = (0 until 14).filter { monday.plusDays(it.toLong()).dayOfWeek != DayOfWeek.WEDNESDAY }
    private val planner = JourneyPlanner(
        TableBuilder(days = 14).apply {
            val sa = station("A", uic = a)
            val sb = station("B", uic = b)
            val sc = station("C", uic = c)
            trip("1", stop(sa, 420), stop(sb, 450), runningDays = weekdays)
            trip("2", stop(sb, 460), stop(sc, 500), runningDays = notWednesday)
        }.build(),
    )
    private val journey = FavoriteTrain("1", "TER", "A", "B", a, b, connections = listOf(FavoriteTrain("2", "TER", "B", "C", b, c)))

    @Test fun `every train of the journey keeps its days`() {
        val restricted = journey.copy(days = listOf(1, 2))
        assertEquals(listOf(listOf(1, 2), listOf(1, 2)), restricted.legs.map { it.days })
    }

    @Test fun `a journey runs on the days all its trains run`() {
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), planner.runningDays(journey))
    }

    @Test fun `the next journey skips the days not taken`() {
        val tuesdays = journey.copy(days = listOf(2))
        val run = planner.nextJourney(tuesdays, monday.atTime(12, 0))!!
        assertEquals(listOf(monday.plusDays(1).atTime(7, 0), monday.plusDays(1).atTime(7, 40)), run.map { it.trip.departure })
    }

    @Test fun `a cancelled connection on a day not taken is not put back`() {
        val tuesdays = journey.copy(days = listOf(2))
        val wednesday = monday.plusDays(2)
        val cancelled = LiveTrain(
            "2", "2", wednesday, cancelled = true, hasRealtime = true,
            stops = listOf(LiveStop("$b", null, 460 * 60), LiveStop("$c", 500 * 60, null)),
        )
        val feed = LiveFeed(OffsetDateTime.now(), OffsetDateTime.now().plusHours(1), listOf(cancelled))
        assertNull(FavoriteWatch.missingRun(tuesdays.legs[1], null, feed, wednesday.atTime(6, 0)))
    }
}
