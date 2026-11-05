package com.scresto.datrain.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class RecentHistoryTest {
    private fun station(name: String) = Station(name, 0, 0)
    private fun names(history: RecentHistory) = history.stations.map { it.name }
    private fun routes(history: RecentHistory) = history.journeys.map { "${it.from.name}>${it.to.name}" }

    @Test fun `the latest search comes first`() {
        val history = RecentHistory().adding(station("A"), station("B")).adding(station("C"), station("D"))
        assertEquals(listOf("C", "D", "A", "B"), names(history))
        assertEquals(listOf("C>D", "A>B"), routes(history))
    }

    @Test fun `searching again moves entries up without duplicates`() {
        val history = RecentHistory()
            .adding(station("A"), station("B"))
            .adding(station("C"), station("D"))
            .adding(station("A"), station("B"))
        assertEquals(listOf("A", "B", "C", "D"), names(history))
        assertEquals(listOf("A>B", "C>D"), routes(history))
    }

    @Test fun `a return trip is a separate journey`() {
        val history = RecentHistory().adding(station("A"), station("B")).adding(station("B"), station("A"))
        assertEquals(listOf("B>A", "A>B"), routes(history))
        assertEquals(listOf("B", "A"), names(history))
    }

    @Test fun `old entries drop out`() {
        var history = RecentHistory()
        for (i in 1..12) history = history.adding(station("S$i"), station("T$i"))
        assertEquals(listOf("S12", "T12", "S11", "T11", "S10"), names(history))
        assertEquals((12 downTo 3).map { "S$it>T$it" }, routes(history))
    }

    @Test fun `removing an entry keeps the others`() {
        val history = RecentHistory().adding(station("A"), station("B"))
        assertEquals(listOf("B"), names(history.without(history.stations[0])))
        assertEquals(1, history.without(history.stations[0]).journeys.size)
        assertEquals(emptyList<String>(), routes(history.without(history.journeys[0])))
        assertEquals(2, history.without(history.journeys[0]).stations.size)
    }

    @Test fun `the history survives being saved as text`() {
        val history = RecentHistory().adding(Station("Île", 87000001, 3), station("B"))
        assertEquals(history, Json.decodeFromString<RecentHistory>(Json.encodeToString(history)))
    }

    private fun train(number: String, arrival: String = "2026-11-05T12:00") = RecentTrain(
        number, "TER", "Fin", "A", "2026-11-05", "2026-11-05T10:00", arrival, "1", "2",
        listOf(LiveStop("1", null, 36000)), mapOf("1" to StationInfo("A", LatLon(1.0, 2.0))),
    )

    @Test fun `the last five trains are kept, latest first, without duplicates`() {
        var history = RecentHistory()
        for (i in 1..6) history = history.addingTrain(train("$i"))
        assertEquals(listOf("6", "5", "4", "3", "2"), history.trains.map { it.number })
        assertEquals(listOf("4", "6", "5", "3", "2"), history.addingTrain(train("4")).trains.map { it.number })
    }

    @Test fun `a new search keeps the trains`() {
        val history = RecentHistory().addingTrain(train("1")).adding(station("A"), station("B"))
        assertEquals(1, history.trains.size)
    }

    @Test fun `a train disappears three hours after its arrival`() {
        val history = RecentHistory().addingTrain(train("1"))
        assertEquals(1, history.withoutFinishedTrains(LocalDateTime.of(2026, 11, 5, 14, 59)).trains.size)
        assertEquals(0, history.withoutFinishedTrains(LocalDateTime.of(2026, 11, 5, 15, 1)).trains.size)
    }

    @Test fun `a saved train opens again with its route`() {
        val saved = Json.decodeFromString<RecentHistory>(Json.encodeToString(RecentHistory().addingTrain(train("1"))))
        val trip = saved.trains.single().toTrip()
        assertEquals("1", trip.number)
        assertEquals(LocalDate.of(2026, 11, 5), trip.serviceDate)
        assertEquals(LocalDateTime.of(2026, 11, 5, 12, 0), trip.arrival)
        assertEquals(1, trip.plannedStops.size)
        assertEquals("A", trip.plannedStations["1"]?.name)
    }

    @Test fun `a history saved before trains existed still loads`() {
        val old = """{"stations":[{"name":"A","uic":0}],"journeys":[]}"""
        assertEquals(emptyList<RecentTrain>(), Json.decodeFromString<RecentHistory>(old).trains)
    }

    @Test fun `the last ten train numbers are kept, latest first, without duplicates`() {
        var history = RecentHistory()
        for (i in 1..12) history = history.addingNumber("$i")
        assertEquals((12 downTo 3).map { "$it" }, history.numbers)
        assertEquals("5", history.addingNumber("5").numbers.first())
        assertEquals(10, history.addingNumber("5").numbers.size)
        assertEquals(9, history.withoutNumber("5").numbers.size)
    }

    private val tripOnRoute = Trip(
        "42", "TER", "C", LocalDate.of(2026, 11, 5),
        LocalDateTime.of(2026, 11, 5, 8, 0), LocalDateTime.of(2026, 11, 5, 9, 0),
        fromKey = "87000001", toKey = "87000002",
        plannedStops = listOf(LiveStop("87000009", null, 100), LiveStop("87000001", 200, 210), LiveStop("87000002", 300, null)),
        plannedStations = mapOf(
            "87000009" to StationInfo("Z", null), "87000001" to StationInfo("A", null), "87000002" to StationInfo("B", null),
        ),
    )

    @Test fun `a favorite keeps the traveller's stations`() {
        val favorite = FavoriteTrain.of(tripOnRoute)!!
        assertEquals("A", favorite.fromName)
        assertEquals("B", favorite.toName)
        assertEquals(87000001, favorite.fromUic)
    }

    @Test fun `from a number search a favorite covers the whole route`() {
        val favorite = FavoriteTrain.of(tripOnRoute.copy(fromKey = "", toKey = ""))!!
        assertEquals("Z", favorite.fromName)
        assertEquals("B", favorite.toName)
    }

    @Test fun `a train without known stops cannot be a favorite`() {
        assertEquals(null, FavoriteTrain.of(tripOnRoute.copy(fromKey = "", toKey = "", plannedStops = emptyList())))
    }

    @Test fun `favorites have no duplicates, can be removed and do not expire`() {
        val favorite = FavoriteTrain.of(tripOnRoute)!!
        val history = RecentHistory().addingFavorite(favorite).addingFavorite(favorite)
        assertEquals(1, history.favorites.size)
        assertEquals(true, history.isFavorite(favorite))
        assertEquals(0, history.withoutFavorite(favorite).favorites.size)
        assertEquals(1, history.withoutFinishedTrains(LocalDateTime.of(2030, 1, 1, 0, 0)).favorites.size)
    }

    @Test fun `a saved station is found again in the timetable`() {
        val b = TableBuilder(LocalDate.of(2026, 11, 2), 10)
        val a = b.station("A", uic = 87000001)
        val c = b.station("C")
        b.trip("1", stop(a, 480), stop(c, 540))
        val planner = JourneyPlanner(b.build())
        assertEquals(a, planner.resolve(RecentStation("A", 87000001).toStation()))
        assertEquals(c, planner.resolve(RecentStation("C", 0).toStation()))
        assertEquals(null, planner.resolve(RecentStation("Gone", 0).toStation()))
    }
}
