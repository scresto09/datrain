package com.scresto.datrain.data

import java.io.File
import java.time.LocalDate
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Writes real journeys if DATRAIN_TIMETABLE points to a folder of published files; skipped otherwise. */
class RealTimetableCheck {
    @Test
    fun printJourneys() {
        val path = System.getenv("DATRAIN_TIMETABLE")
        assumeTrue(path != null)
        val out = StringBuilder()
        fun <T> timed(label: String, block: () -> T): T {
            val t = System.nanoTime()
            return block().also { out.appendLine("$label : ${(System.nanoTime() - t) / 1_000_000} ms") }
        }
        val sncf = timed("read SNCF") { File(path, "sncf.bin.gz").inputStream().use(RailTimetable::readGzip) }
        val idfm = timed("read IDFM") { File(path, "idfm.bin.gz").inputStream().use(RailTimetable::readGzip) }
        val corrections = TimetableCorrections(mapOf("Aéroport CDG (Terminal 2) - TGV" to "Aéroport Charles de Gaulle 2 TGV"))
        val merged = timed("merge") { TimetableMerger.merge(sncf, idfm, corrections) }
        val cacheFile = File.createTempFile("datrain", ".bin")
        timed("write cache") { TimetableCache.write(merged, cacheFile) }
        val table = timed("read cache") { TimetableCache.read(cacheFile)!! }
        cacheFile.delete()
        out.appendLine("${table.tripCount} trains, ${table.stationCount} stations, ${table.firstDate} -> ${table.lastDate}")
        val search = timed("prepare day") { JourneySearch(table, LocalDate.of(2026, 11, 6)) }
        out.appendLine("${search.connectionCount} connections")
        fun station(name: String) = table.stationNames.indexOfFirst { it.equals(name, ignoreCase = true) }
            .also { require(it >= 0) { name } }
        fun hm(s: Int) = "%02d:%02d".format(((s / 3600) % 24 + 24) % 24, s % 3600 / 60)
        val pairs = listOf(
            "Carbonne" to "Lyon Part Dieu", "Carbonne" to "Lille Europe", "Brest" to "Nice-Ville",
            "Carbonne" to "Cergy le Haut", "Melun" to "Versailles Chantiers",
        )
        for ((a, b) in pairs) {
            val results = timed("$a -> $b") { search.search(station(a), station(b), 6 * 3600) }
            out.appendLine("== $a -> $b : ${results.size} options")
            results.take(12).forEach { j ->
                out.appendLine(" ${hm(j.departure)} -> ${hm(j.arrival)}, ${j.changes} changes: " + j.legs.joinToString(" | ") { l ->
                    if (l.trip < 0) "walk ${table.stationNames[l.toStation]}"
                    else "${table.strings[table.tripLabel[l.trip]]} ${table.strings[table.tripNumber[l.trip]]} ${table.stationNames[l.toStation]}"
                })
            }
        }
        File(System.getenv("DATRAIN_OUT")!!).writeText(out.toString())
    }
}
