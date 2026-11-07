package com.scresto.datrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LiveTrainTest {
    private val day = LocalDate.of(2026, 11, 3)

    private fun stop(uic: String, a: Int? = null, d: Int? = null, ra: Int = 0, rd: Int = 0, passed: Boolean = false) =
        LiveStop(uic, a, d, ra, rd, passed = passed)

    private val stops = listOf(
        stop("A", d = 1000, rd = 100),
        stop("B", a = 2000, d = 2100, ra = 100, rd = 100),
        stop("C", a = 3000),
    )

    private fun progressAt(now: Int, list: List<LiveStop> = stops) = ProgressCalculator.compute(list, now)

    @Test fun `after midnight the newer copy of a train in the next day wins`() {
        val trip = Trip("133669", "TRANSILIEN", "Versailles", day, day.atTime(23, 56), day.plusDays(1).atTime(0, 34), fromKey = "A", toKey = "C")
        val old = LiveTrain("133669", "133669", day, false, true, listOf(stop("A", d = 86160, rd = 180), stop("C", a = 88440, ra = 180)), updatedAt = 100)
        val copy = LiveTrain(
            "133669/2", "133669", day.plusDays(1), false, true,
            listOf(
                stop("A", d = -240, rd = 360),
                LiveStop("A", null, 86160, removed = true),
                stop("C", a = 2040, ra = 420),
            ),
            updatedAt = 200,
        )
        val found = LiveFeed(java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now(), listOf(old, copy)).find(trip)!!
        assertEquals(day, found.serviceDate)
        assertEquals(listOf(86160, null), found.stops.map { it.departureSeconds })
        assertEquals(420, found.stops.last().arrivalDelaySeconds)

        // Tonight's train, same number and time, is not last night's copy.
        val tonight = trip.copy(serviceDate = day.plusDays(1), departure = day.plusDays(1).atTime(23, 56), arrival = day.plusDays(2).atTime(0, 34))
        assertNull(LiveFeed(java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now(), listOf(old, copy)).find(tonight))
    }

    @Test fun `before the delayed departure the train has not started`() {
        assertEquals(TrainProgress.NotStarted, progressAt(1050))
    }

    @Test fun `interpolates between stops using estimated times`() {
        // Estimated departure 1100, estimated arrival 2100.
        assertEquals(TrainProgress.Between(0, 1, 0.25), progressAt(1350))
    }

    @Test fun `stays at the stop between estimated arrival and departure`() {
        assertEquals(TrainProgress.AtStop(1), progressAt(2150))
    }

    @Test fun `reaches the last stop then is arrived`() {
        assertTrue(progressAt(2999) is TrainProgress.Between)
        assertEquals(TrainProgress.Arrived, progressAt(3000))
    }

    @Test fun `a confirmed passage moves the train forward when the clock is behind`() {
        val list = listOf(stops[0], stops[1].copy(passed = true), stops[2])
        assertEquals(TrainProgress.AtStop(1), progressAt(1200, list))
    }

    @Test fun `a confirmed passage does not hold the train back`() {
        val list = listOf(stops[0].copy(passed = true), stops[1], stops[2])
        assertEquals(TrainProgress.AtStop(1), progressAt(2150, list))
    }

    @Test fun `missing confirmation does not block the clock`() {
        assertEquals(TrainProgress.Arrived, progressAt(9999))
    }

    @Test fun `passage confirmed at terminus means arrived`() {
        val list = listOf(stops[0], stops[1], stops[2].copy(passed = true))
        assertEquals(TrainProgress.Arrived, progressAt(1200, list))
    }

    @Test fun `removed stops are excluded`() {
        val train = LiveTrain("1", "1", day, false, true, listOf(stops[0], stops[1].copy(removed = true), stops[2]))
        assertEquals(listOf("A", "C"), train.activeStops.map { it.uic })
    }

    private val trip = Trip("6611", "TGV", "X", day, day.atTime(10, 0), day.atTime(12, 0))
    private fun live(key: String, departure: Int, date: LocalDate = day) =
        LiveTrain(key, key.substringBefore('/'), date, false, true, listOf(stop("A", d = departure)))
    private fun feed(vararg trains: LiveTrain) = LiveFeed(
        java.time.OffsetDateTime.parse("2026-11-03T10:00:00+01:00"),
        java.time.OffsetDateTime.parse("2026-11-03T10:10:00+01:00"),
        trains.toList(),
    )

    @Test fun `finds the occurrence matching the departure time among duplicate numbers`() {
        assertEquals("6611/2", feed(live("6611", 20000), live("6611/2", 36000)).find(trip)?.key)
    }

    @Test fun `falls back on the number when the schedule shifted and there is no ambiguity`() {
        assertEquals("6611", feed(live("6611", 36060)).find(trip)?.key)
    }

    @Test fun `does not guess between ambiguous occurrences`() {
        assertNull(feed(live("6611", 1), live("6611/2", 2)).find(trip))
    }

    @Test fun `a train can be tracked today or while running overnight, not otherwise`() {
        val night = Trip("3751", "IC", "X", day, day.atTime(22, 13), day.plusDays(1).atTime(7, 44))

        assertTrue(night.isTrackableAt(day.atTime(8, 0)))
        assertTrue(night.isTrackableAt(day.plusDays(1).atTime(1, 0)))
        assertFalse(night.isTrackableAt(day.plusDays(1).atTime(9, 0)))
        assertFalse(night.isTrackableAt(day.minusDays(1).atTime(23, 0)))
    }

    @Test fun `ignores a train of another service date`() {
        assertNull(feed(live("6611", 36000, day.plusDays(1))).find(trip))
    }

    private val a = LatLon(48.0, 2.0)
    private val b = LatLon(48.0, 3.0)
    private val c = LatLon(49.0, 3.0)

    @Test fun `point along a track follows the distance`() {
        val segment = RouteSegment(listOf(a, b), isRealTrack = true)
        assertEquals(2.5, segment.pointAt(0.5).lon, 1e-6)
        assertEquals(a, segment.pointAt(0.0).let { LatLon(it.lat, it.lon) })
        assertEquals(3.0, segment.pointAt(1.0).lon, 1e-6)
    }

    @Test fun `route uses real tracks reversed if needed and straight lines otherwise`() {
        val stations = mapOf("A" to StationInfo("Gare A", a), "B" to StationInfo("Gare B", b), "C" to StationInfo("Gare C", c))
        val route = TrainRoute.build(
            listOf(stop("A", d = 1), stop("B", a = 2, d = 3), stop("C", a = 4)),
            stations,
            listOf(Track("B", "A", listOf(b, LatLon(48.5, 2.5), a))),
        )

        assertTrue(route.segments[0]!!.isRealTrack)
        assertEquals(a, route.segments[0]!!.points.first())
        assertFalse(route.segments[1]!!.isRealTrack)
        assertEquals(listOf(b, c), route.segments[1]!!.points)
    }

    @Test fun `no invented position when coordinates are missing`() {
        val route = TrainRoute.build(
            listOf(stop("A", d = 1), stop("X", a = 2)),
            mapOf("A" to StationInfo("Gare A", a)),
            emptyList(),
        )

        assertNull(route.segments[0])
        assertNull(route.positionOf(TrainProgress.Between(0, 1, 0.5)))
        assertNotNull(route.positionOf(TrainProgress.NotStarted))
        assertEquals("Gare X", route.stops[1].name)
    }

    @Test fun `a station missing from the list is located by the end of a track`() {
        val route = TrainRoute.build(
            listOf(stop("X", d = 1), stop("B", a = 2)),
            mapOf("B" to StationInfo("Gare B", b)),
            listOf(Track("X", "B", listOf(a, b))),
        )

        assertEquals(a, route.stops[0].position)
    }

    @Test fun `parses the live feed`() {
        val feed = LiveParser.parseFeed(
            200,
            """{"jour":"2026-11-03","collecte_le":"2026-11-03T23:20:00+01:00","perime_apres":"2026-11-03T23:34:48+01:00",
               "nb_trains":2,"trains":{
                "2421":{"p":"highSpeedRail","on":"Paris Est","t":1,"r":1500,"ar":[
                   {"s":"87113001","d":45960,"rd":1200,"v":"28","p":1},{"s":"87212027","a":52800,"ra":1500,"x":1}]},
                "841/2":{"p":"local","j":"2026-11-04","an":1,"nm":1,"ar":[]}}}""",
        )
        val first = feed.trains.first { it.key == "2421" }
        val second = feed.trains.first { it.key == "841/2" }

        assertEquals(day, first.serviceDate)
        assertEquals(47160, first.stops[0].estimatedDeparture)
        assertTrue(first.stops[0].passed)
        assertTrue(first.stops[1].removed)
        assertEquals("841", second.number)
        assertEquals(day.plusDays(1), second.serviceDate)
        assertTrue(second.cancelled)
        assertFalse(second.hasRealtime)
    }

    @Test fun `parses only wanted tracks with latitude and longitude in the right order`() {
        val body = """{"features":[
          {"properties":{"code_uic_a":"A","code_uic_b":"B","longueur_m":1},"geometry":{"type":"LineString","coordinates":[[2.0,48.0],[3.0,48.0]]}},
          {"properties":{"code_uic_a":"C","code_uic_b":"D"},"geometry":{"type":"LineString","coordinates":[[1.0,1.0],[2.0,2.0]]}}]}"""

        val tracks = LiveParser.parseTracks(200, body, setOf("B" to "A"))

        assertEquals(listOf("A"), tracks.map { it.uicA })
        assertEquals(a, tracks.single().points.first())
    }

    @Test fun `parses stations with secondary codes and quoted names`() {
        val body = "code_uic,autres_codes_uic,nom,code_commune,latitude,longitude\n" +
            "87686006,87686030,\"Paris, Gare de Lyon\",75112,48.84,2.37\n" +
            "87000001,,Sans position,1,,\n"

        val stations = LiveParser.parseStations(200, body)

        assertEquals("Paris, Gare de Lyon", stations["87686030"]?.name)
        assertEquals(LatLon(48.84, 2.37), stations["87686006"]?.position)
        assertNull(stations["87000001"]?.position)
    }

    @Test fun `live errors are mapped`() {
        assertThrows(NomadeError.RateLimited::class.java) { LiveParser.parseFeed(429, "") }
        assertThrows(NomadeError.Api::class.java) { LiveParser.parseFeed(200, "<html>") }
        assertThrows(NomadeError.Api::class.java) { LiveParser.parseStations(200, "pas,le,bon,format") }
    }

    @Test fun `the map opens around the stops, closer for a short route`() {
        val short = InitialMapView.around(listOf(LatLon(43.2, 1.2), LatLon(43.6, 1.4)))
        assertEquals(43.4, short.center.lat, 0.001)
        val long = InitialMapView.around(listOf(LatLon(43.6, 1.4), LatLon(50.6, 3.0)))
        assertTrue(short.zoom > long.zoom)
    }

    @Test fun `without known stops the map opens on France`() {
        val view = InitialMapView.around(emptyList())
        assertEquals(46.6, view.center.lat, 0.001)
        assertTrue(view.zoom > 3.0)
    }
}
