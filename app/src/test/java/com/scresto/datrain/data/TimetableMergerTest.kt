package com.scresto.datrain.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.time.LocalDate

class TimetableMergerTest {
    private val first = LocalDate.of(2026, 11, 2)

    private fun sncf() = TableBuilder(first, 10).apply {
        val lyon = station("Paris Gare de Lyon Hall 1 - 2", 48.8444f, 2.3735f, 87686006)
        val melun = station("Melun", 48.5276f, 2.6554f, 87682005)
        trip("100", stop(lyon, 480), stop(melun, 510), label = "TER")
    }.build()

    private fun idfm(from: LocalDate = first, days: Int = 10) = TableBuilder(from, days).apply {
        val lyon = station("Gare de Lyon", 48.8446f, 2.3739f)
        val versailles = station("Versailles Chantiers", 48.7950f, 2.1350f)
        trip("100", stop(lyon, 480), stop(versailles, 510), label = "Doublon")
        trip("ABCD", stop(lyon, 600), stop(versailles, 640), label = "RER C", mode = RailTimetable.MODE_SUBURBAN)
        walk(lyon, versailles, 120)
    }.build()

    @Test fun `a close station with a similar name becomes one station`() {
        val merged = TimetableMerger.merge(sncf(), idfm())
        assertEquals(3, merged.stationCount)
        assertEquals("Paris Gare de Lyon Hall 1 - 2", merged.stationNames[0])
        assertEquals("Versailles Chantiers", merged.stationNames[2])
    }

    @Test fun `a close station with another name stays separate`() {
        val idfm = TableBuilder(first, 10).apply {
            val magenta = station("Magenta", 48.8446f, 2.3739f)
            val other = station("Autre", 48.0f, 2.0f)
            trip("X", stop(magenta, 480), stop(other, 510))
        }.build()
        assertEquals(4, TimetableMerger.merge(sncf(), idfm).stationCount)
    }

    @Test fun `a far station with the same name stays separate`() {
        val idfm = TableBuilder(first, 10).apply {
            val far = station("Melun", 40.0f, 2.0f)
            val other = station("Autre", 41.0f, 2.0f)
            trip("X", stop(far, 480), stop(other, 510))
        }.build()
        assertEquals(4, TimetableMerger.merge(sncf(), idfm).stationCount)
    }

    @Test fun `a correction merges two names that cannot be matched`() {
        val idfm = TableBuilder(first, 10).apply {
            val a = station("Lyon RER bizarre", 48.0f, 2.0f)
            val b = station("Autre", 41.0f, 2.0f)
            trip("X", stop(a, 480), stop(b, 510))
        }.build()
        val corrections = TimetableCorrections(mapOf("Lyon RER bizarre" to "Paris Gare de Lyon Hall 1 - 2"))
        assertEquals(3, TimetableMerger.merge(sncf(), idfm, corrections).stationCount)
    }

    @Test fun `a train in both sources keeps its SNCF version`() {
        val merged = TimetableMerger.merge(sncf(), idfm())
        val labels = (0 until merged.tripCount).map { merged.strings[merged.tripLabel[it]] }
        assertEquals(listOf("TER", "RER C"), labels)
    }

    @Test fun `a duplicate is only removed on the days the SNCF runs it`() {
        val sncf = TableBuilder(first, 10).apply {
            val a = station("A", 48f, 2f, 87000001)
            val b = station("B", 49f, 2f, 87000002)
            trip("7", stop(a, 480), stop(b, 510), runningDays = listOf(0))
        }.build()
        val idfm = TableBuilder(first, 10).apply {
            val a = station("Z", 40f, 2f)
            val b = station("Y", 41f, 2f)
            trip("7", stop(a, 480), stop(b, 510), runningDays = listOf(0, 1))
        }.build()
        val merged = TimetableMerger.merge(sncf, idfm)
        assertEquals(2, merged.tripCount)
        assertFalse(merged.runsOn(1, 0))
        assertTrue(merged.runsOn(1, 1))
    }

    @Test fun `a train with a double number counts for each number`() {
        val sncf = TableBuilder(first, 10).apply {
            val a = station("A", 48f, 2f, 87000001)
            val b = station("B", 49f, 2f, 87000002)
            trip("3451", stop(a, 480), stop(b, 510))
        }.build()
        val idfm = TableBuilder(first, 10).apply {
            val a = station("Z", 40f, 2f)
            val b = station("Y", 41f, 2f)
            trip("3451-3450", stop(a, 480), stop(b, 510))
        }.build()
        assertEquals(1, TimetableMerger.merge(sncf, idfm).tripCount)
    }

    @Test fun `calendars starting on different days stay on the right date`() {
        val later = first.plusDays(3)
        val idfm = TableBuilder(later, 4).apply {
            val a = station("Z", 40f, 2f)
            val b = station("Y", 41f, 2f)
            trip("9", stop(a, 480), stop(b, 510), runningDays = listOf(0))
        }.build()
        val merged = TimetableMerger.merge(sncf(), idfm)
        val trip = merged.tripCount - 1
        assertTrue(merged.runsOn(trip, 3))
        assertFalse(merged.runsOn(trip, 0))
        assertEquals(first, merged.firstDate)
        assertEquals(first.plusDays(9), merged.lastDate)
    }

    @Test fun `merged trains keep their times and stops`() {
        val merged = TimetableMerger.merge(sncf(), idfm())
        val trip = merged.tripCount - 1
        assertEquals(600 * 60, merged.times[merged.tripOffset[trip] + 1])
        assertEquals(640 * 60, merged.times[merged.tripOffset[trip] + 2])
        assertArrayEquals(intArrayOf(0, 2), merged.patternStations[merged.tripPattern[trip]])
    }

    @Test fun `official walking time is kept with merged station numbers`() {
        val merged = TimetableMerger.merge(sncf(), idfm())
        assertEquals(listOf(2, 120), merged.transfers[0].toList())
    }

    @Test fun `paris terminals are linked by the estimated transfer`() {
        val sncf = TableBuilder(first, 10).apply {
            val lyon = station("Lyon", 48.8444f, 2.3735f, 87686006)
            val nord = station("Nord", 48.8809f, 2.3553f, 87271007)
            trip("1", stop(lyon, 480), stop(nord, 500))
        }.build()
        val idfm = TableBuilder(first, 10).apply {
            val a = station("Z", 40f, 2f)
            val b = station("Y", 41f, 2f)
            trip("2", stop(a, 480), stop(b, 510))
        }.build()
        val merged = TimetableMerger.merge(sncf, idfm)
        assertEquals(listOf(1, TimetableMerger.PARIS_TRANSFER_SECONDS), merged.transfers[0].toList())
    }

    @Test fun `stations close to each other can be walked between`() {
        val sncf = TableBuilder(first, 10).apply {
            val a = station("Alpha", 48.0f, 2.0f, 87000001)
            val b = station("Beta", 48.001f, 2.0f, 87000002)
            trip("1", stop(a, 480), stop(b, 500))
        }.build()
        val idfm = TableBuilder(first, 10).apply {
            val a = station("Z", 40f, 2f)
            val b = station("Y", 41f, 2f)
            trip("2", stop(a, 480), stop(b, 510))
        }.build()
        assertEquals(listOf(1, 900), TimetableMerger.merge(sncf, idfm).transfers[0].toList())
    }

    @Test fun `the saved timetable reads back identical`() {
        val merged = TimetableMerger.merge(sncf(), idfm())
        val file = File.createTempFile("datrain", ".bin")
        try {
            TimetableCache.write(merged, file)
            val read = TimetableCache.read(file)!!
            assertEquals(merged.firstDate, read.firstDate)
            assertEquals(merged.dayCount, read.dayCount)
            assertArrayEquals(merged.strings, read.strings)
            assertArrayEquals(merged.stationNames, read.stationNames)
            assertArrayEquals(merged.stationLat, read.stationLat, 0f)
            assertArrayEquals(merged.stationUic, read.stationUic)
            assertArrayEquals(merged.times, read.times)
            assertArrayEquals(merged.tripPattern, read.tripPattern)
            assertArrayEquals(merged.tripMode, read.tripMode)
            assertEquals(merged.transfers.map { it.toList() }, read.transfers.map { it.toList() })
            assertEquals(merged.calendars.map { it.toList() }, read.calendars.map { it.toList() })
            assertEquals(merged.patternStations.map { it.toList() }, read.patternStations.map { it.toList() })
        } finally {
            file.delete()
        }
    }

    @Test fun `a damaged saved timetable is rejected rather than used`() {
        val file = File.createTempFile("datrain", ".bin")
        try {
            file.writeBytes(byteArrayOf(0, 0, 0, 1, 0, 0))
            assertNull(TimetableCache.read(file))
            assertNull(TimetableCache.read(File(file.parentFile, "absent-${file.name}")))
        } finally {
            file.delete()
        }
    }

    @Test fun `reads the published file format`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).run {
            writeBytes("DTRN")
            writeInt(2)
            writeInt(20261102)
            writeShort(8)
            writeInt(2) // strings
            writeUTF("42")
            writeUTF("TER")
            writeInt(2) // stations
            writeUTF("A"); writeFloat(45f); writeFloat(2f); writeInt(87000001)
            writeUTF("B"); writeFloat(46f); writeFloat(2f); writeInt(0)
            writeInt(1) // walks
            writeInt(0); writeInt(1); writeShort(120)
            writeInt(1) // calendars
            writeByte(0b00000101)
            writeInt(1) // patterns
            writeShort(2)
            writeInt(0); writeByte(1)
            writeInt(1); writeByte(2)
            writeInt(1) // trains
            writeByte(0); writeByte(0); writeByte(0); writeByte(1); writeByte(0)
            writeByte(0x90.toByte().toInt()); writeByte(0x01) // 144 s: first departure as a two-byte varint
            writeByte(60); writeByte(0)
        }
        val table = RailTimetable.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(LocalDate.of(2026, 11, 2), table.firstDate)
        assertEquals("42", table.strings[table.tripNumber[0]])
        assertEquals(listOf(144, 144, 204, 204), table.times.toList())
        assertTrue(table.runsOn(0, 2))
        assertFalse(table.runsOn(0, 1))
        assertEquals(listOf(1, 120), table.transfers[0].toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown file version is refused`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).run {
            writeBytes("DTRN")
            writeInt(99)
        }
        RailTimetable.read(ByteArrayInputStream(bytes.toByteArray()))
    }
}
