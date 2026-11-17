package com.scresto.datrain.data

import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Run by the timetables workflow, with the app's own rule: the SNCF trains of the previous publication gone
 * from the new one, added to those published before. New installs then know them too. Skipped without
 * DATRAIN_PUBLICATION, the folder of the new files; DATRAIN_PREVIOUS holds the published sncf.bin.gz and
 * removed.json.xz, when there are some. Compressed: a strike day lists about a thousand trains.
 */
class PublishRemovedRuns {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun publish() {
        val publication = System.getenv("DATRAIN_PUBLICATION")?.let(::File)
        assumeTrue(publication != null)
        val previous = System.getenv("DATRAIN_PREVIOUS")?.let(::File)
        val sncf = File(publication, "sncf.bin.gz").inputStream().use(RailTimetable::readGzip)
        val idfm = File(publication, "idfm.bin.gz").inputStream().use(RailTimetable::readGzip)
        val corrections = json.decodeFromString<Corrections>(File(publication, "corrections.json").readText())
        val merged = TimetableMerger.merge(sncf, idfm, TimetableCorrections(corrections.sameStation))
        val old = previous?.let { File(it, "sncf.bin.gz") }?.takeIf { it.exists() }?.inputStream()?.use(RailTimetable::readGzip)
        val known = previous?.let { File(it, RemovedRuns.PUBLISHED) }?.takeIf { it.exists() }
            ?.let { RemovedRuns.readPublished(it.readBytes()) }.orEmpty()
        val removed = RemovedRuns.update(known, old, merged, LocalDate.now(PARIS))
        File(publication, RemovedRuns.PUBLISHED).writeBytes(RemovedRuns.writePublished(removed))
        println("${RemovedRuns.PUBLISHED}: ${removed.size} runs cancelled in advance")
    }

    @kotlinx.serialization.Serializable
    private data class Corrections(val sameStation: Map<String, String> = emptyMap())
}
