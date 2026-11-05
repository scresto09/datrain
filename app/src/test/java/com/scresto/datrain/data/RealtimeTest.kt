package com.scresto.datrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

class RealtimeTest {
    private val day = LocalDate.of(2026, 11, 5)
    private val depart = LocalDateTime.of(2026, 11, 5, 10, 0)
    private val arrive = LocalDateTime.of(2026, 11, 5, 11, 0)

    private fun trip(number: String = "42", start: LocalDateTime = depart, end: LocalDateTime = arrive) = Trip(
        number, "TER", "B", day, start, end, fromKey = "87000001", toKey = "87000002",
    )

    private fun itinerary(vararg legs: ItineraryLeg) = Itinerary(legs.toList())

    private fun ride(trip: Trip) = RideLeg(trip, "A", "B", isBus = false)

    private fun feed(vararg trains: LiveTrain) = LiveFeed(
        OffsetDateTime.now(PARIS),
        OffsetDateTime.now(PARIS).plusHours(1),
        trains.toList(),
    )

    private fun live(number: String = "42", cancelled: Boolean = false, stops: List<LiveStop>) =
        LiveTrain(number, number, day, cancelled, true, stops)

    private val liveStops = listOf(
        LiveStop("87000001", null, 36000, departureDelaySeconds = 300, platform = "4"),
        LiveStop("87000002", 39600, null, arrivalDelaySeconds = 600),
    )

    @Test fun `the live feed gives delays and platform`() {
        val result = RealtimeOverlay.apply(listOf(itinerary(ride(trip()))), feed(live(stops = liveStops)), emptyList())
        val updated = result.single().rides.single().trip
        assertEquals(5, updated.departureDelayMinutes)
        assertEquals(10, updated.arrivalDelayMinutes)
        assertEquals("4", updated.platform)
        assertFalse(updated.cancelled)
    }

    @Test fun `a cancelled train in the live feed is marked`() {
        val result = RealtimeOverlay.apply(listOf(itinerary(ride(trip()))), feed(live(cancelled = true, stops = liveStops)), emptyList())
        assertTrue(result.single().cancelled)
    }

    @Test fun `a removed stop where the traveller boards cancels the ride`() {
        val stops = listOf(liveStops[0].copy(removed = true), liveStops[1])
        val result = RealtimeOverlay.apply(listOf(itinerary(ride(trip()))), feed(live(stops = stops)), emptyList())
        assertTrue(result.single().cancelled)
    }

    @Test fun `a stale live feed is ignored`() {
        val stale = LiveFeed(
            OffsetDateTime.now(PARIS).minusHours(2),
            OffsetDateTime.now(PARIS).minusHours(1),
            listOf(live(stops = liveStops)),
        )
        val updated = RealtimeOverlay.apply(listOf(itinerary(ride(trip()))), stale, emptyList()).single().rides.single().trip
        assertEquals(0, updated.departureDelayMinutes)
    }

    @Test fun `a train with a double number is found in the feed`() {
        val result = RealtimeOverlay.apply(listOf(itinerary(ride(trip("41-42")))), feed(live(stops = liveStops)), emptyList())
        assertEquals(5, result.single().rides.single().trip.departureDelayMinutes)
    }

    @Test fun `the SNCF feed covers a train absent from the live feed`() {
        val epoch = depart.atZone(PARIS).toEpochSecond()
        val update = RealtimeTrip(
            "42", "20261105", cancelled = false,
            stops = listOf(
                RealtimeStop("87000001", null, epoch + 240),
                RealtimeStop("87000002", epoch + 3600 + 480, null),
            ),
        )
        val updated = RealtimeOverlay.apply(listOf(itinerary(ride(trip()))), feed(), listOf(update)).single().rides.single().trip
        assertEquals(4, updated.departureDelayMinutes)
        assertEquals(8, updated.arrivalDelayMinutes)
    }

    @Test fun `the SNCF feed of another day is not applied`() {
        val update = RealtimeTrip("42", "20261104", cancelled = true, stops = emptyList())
        val updated = RealtimeOverlay.apply(listOf(itinerary(ride(trip()))), null, listOf(update)).single().rides.single().trip
        assertFalse(updated.cancelled)
    }

    @Test fun `no real time data leaves the journeys untouched`() {
        val journeys = listOf(itinerary(ride(trip())))
        assertSame(journeys, RealtimeOverlay.apply(journeys, null, emptyList()))
    }

    @Test fun `the connection margin includes delays and transfers`() {
        val first = trip("1").copy(arrivalDelayMinutes = 10)
        val second = trip("2", arrive.plusMinutes(40), arrive.plusHours(2))
        val journey = itinerary(ride(first), TransferLeg("B", "C", 900, false), ride(second))
        assertEquals(listOf(15L), journey.connectionMargins())
    }

    @Test fun `a short planned connection without delay is not at risk`() {
        val second = trip("2", arrive.plusMinutes(3), arrive.plusHours(2))
        assertFalse(itinerary(ride(trip("1")), ride(second)).hasConnectionAtRisk(5))
    }

    @Test fun `a delay that shrinks the connection puts it at risk`() {
        val first = trip("1").copy(arrivalDelayMinutes = 15)
        val second = trip("2", arrive.plusMinutes(18), arrive.plusHours(2))
        assertTrue(itinerary(ride(first), ride(second)).hasConnectionAtRisk(5))
    }

    @Test fun `a delay that leaves enough time is not at risk`() {
        val first = trip("1").copy(arrivalDelayMinutes = 5)
        val second = trip("2", arrive.plusMinutes(30), arrive.plusHours(2))
        assertFalse(itinerary(ride(first), ride(second)).hasConnectionAtRisk(5))
    }

    @Test fun `a direct train has no connection margin`() {
        assertTrue(itinerary(ride(trip())).connectionMargins().isEmpty())
    }

    // Minimal protobuf for the GTFS-RT feeds.
    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var rest = value
        while (rest > 127) {
            out.write(((rest and 127) or 128).toInt())
            rest = rest shr 7
        }
        out.write(rest.toInt())
        return out.toByteArray()
    }

    private fun number(field: Int, value: Long) = varint((field shl 3).toLong()) + varint(value)
    private fun bytes(field: Int, value: ByteArray) = varint(((field shl 3) or 2).toLong()) + varint(value.size.toLong()) + value
    private fun text(field: Int, value: String) = bytes(field, value.toByteArray())

    @Test fun `reads a trip update`() {
        val descriptor = text(1, "OCESN5300F1187_F:OUI:FR:Line") + text(3, "20261105")
        val departure = number(1, 0) + number(2, 1000)
        val stop = bytes(3, departure) + text(4, "StopPoint:OCETGV INOUI-87481002")
        val update = bytes(1, descriptor) + bytes(2, stop)
        val feed = bytes(1, number(2, 1)) + bytes(2, text(1, "id") + bytes(3, update))
        val trip = GtfsRealtimeParser.parseTripUpdates(feed).single()
        assertEquals("5300", trip.number)
        assertEquals("20261105", trip.startDate)
        assertFalse(trip.cancelled)
        assertEquals(RealtimeStop("87481002", null, 1000), trip.stops.single())
    }

    @Test fun `reads a cancelled trip`() {
        val descriptor = text(1, "OCESN7F") + text(3, "20261105") + number(4, 3)
        val feed = bytes(2, bytes(3, bytes(1, descriptor)))
        assertTrue(GtfsRealtimeParser.parseTripUpdates(feed).single().cancelled)
    }

    @Test fun `a trip without a recognised number is skipped`() {
        val feed = bytes(2, bytes(3, bytes(1, text(1, "autre"))))
        assertTrue(GtfsRealtimeParser.parseTripUpdates(feed).isEmpty())
    }

    @Test fun `reads an alert in french without html`() {
        fun translated(field: Int, de: String, fr: String) =
            bytes(field, bytes(1, text(1, de) + text(2, "de")) + bytes(1, text(1, fr) + text(2, "fr")))
        val informed = bytes(5, bytes(4, text(1, "OCESN844409F"))) + bytes(5, text(5, "StopArea:OCE87276782"))
        val alert = bytes(1, number(1, 100) + number(2, 200)) + informed +
            translated(10, "Titel", "Travaux") + translated(11, "Text", "<p>Voies&nbsp;fermées</p><p>Bus de substitution</p>")
        val parsed = GtfsRealtimeParser.parseAlerts(bytes(2, bytes(5, alert))).single()
        assertEquals("Travaux", parsed.title)
        assertEquals("Voies fermées\nBus de substitution", parsed.description)
        assertEquals(listOf(AlertTarget("844409", null), AlertTarget(null, "87276782")), parsed.targets)
        assertTrue(parsed.isActiveAt(150))
        assertFalse(parsed.isActiveAt(300))
    }

    @Test fun `an alert for a train at one station only concerns travellers using that station`() {
        val alert = ServiceAlert("Voitures hors quai", "", listOf(AlertTarget("42", "87000009")))
        assertFalse(alert.concerns(listOf("42"), "87000001", "87000002"))
        assertTrue(alert.concerns(listOf("42"), "87000009", "87000002"))
        assertFalse(alert.concerns(listOf("7"), "87000009", "87000002"))
    }

    @Test fun `an alert for a train concerns all its travellers`() {
        val alert = ServiceAlert("Travaux", "", listOf(AlertTarget("42", null)))
        assertTrue(alert.concerns(listOf("41", "42"), "A", "B"))
        assertFalse(alert.concerns(listOf("7"), "A", "B"))
    }

    @Test fun `an alert for a station concerns who boards or alights there`() {
        val alert = ServiceAlert("Gare fermée", "", listOf(AlertTarget(null, "87000002")))
        assertTrue(alert.concerns(listOf("42"), "87000001", "87000002"))
        assertFalse(alert.concerns(listOf("42"), "87000001", "87000003"))
    }

    @Test fun `a truncated feed is reported as unreadable`() {
        assertThrows(NomadeError.Api::class.java) { GtfsRealtimeParser.parseTripUpdates(byteArrayOf(0x12, 0x7f, 0x01)) }
    }

    @Test fun `isTrackable still covers a train running after midnight`() {
        val night = Trip("1", "TER", "B", day, LocalDateTime.of(2026, 11, 5, 23, 0), LocalDateTime.of(2026, 11, 6, 2, 0))
        assertTrue(night.isTrackableAt(LocalDateTime.of(2026, 11, 6, 1, 0)))
        assertNull(night.platform)
    }
}
