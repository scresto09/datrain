package com.scresto.datrain.data

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.GZIPOutputStream

/** Published timetables are read the same, gzip or xz. */
class CompressedTimetableTest {
    /** The smallest file the format allows: a date range and nothing else. */
    private val empty: ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            write("DTRN".toByteArray())
            writeInt(2)
            writeInt(20261117)
            writeShort(30)
            repeat(6) { writeInt(0) }
        }
    }.toByteArray()

    private fun gzip(data: ByteArray) = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

    private fun xz(data: ByteArray) = ByteArrayOutputStream().also { out -> XZOutputStream(out, LZMA2Options()).use { it.write(data) } }.toByteArray()

    @Test fun `gzip and xz files read alike`() {
        for (file in listOf(gzip(empty), xz(empty))) {
            val table = RailTimetable.readCompressed(file.inputStream())
            assertEquals(LocalDate.of(2026, 11, 17), table.firstDate)
            assertEquals(30, table.dayCount)
        }
    }

    /** With DATRAIN_XZ_CHECK, a folder holding the same timetables as sncf.bin.gz and sncf.bin.xz. */
    @Test fun `real files read alike`() {
        val folder = System.getenv("DATRAIN_XZ_CHECK")?.let(::File)
        assumeTrue(folder != null)
        val gz = File(folder, "sncf.bin.gz").inputStream().use(RailTimetable::readCompressed)
        val xz = File(folder, "sncf.bin.xz").inputStream().use(RailTimetable::readCompressed)
        assertEquals(gz.tripCount, xz.tripCount)
        assertEquals(gz.times.toList(), xz.times.toList())
        assertEquals(gz.stationNames.toList(), xz.stationNames.toList())
    }
}
