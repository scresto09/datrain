package com.scresto.datrain.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.time.LocalDate

/**
 * Saves the combined timetables, to avoid merging at every launch:
 * raw arrays, much faster to read back than the compressed files.
 */
object TimetableCache {
    private const val FORMAT = 1

    fun write(table: RailTimetable, target: File) {
        val temporary = File(target.parentFile, target.name + ".tmp")
        val writer = Writer()
        writer.int(FORMAT)
        writer.int(table.firstDate.year * 10000 + table.firstDate.monthValue * 100 + table.firstDate.dayOfMonth)
        writer.int(table.dayCount)
        writer.strings(table.strings)
        writer.strings(table.stationNames)
        writer.floats(table.stationLat)
        writer.floats(table.stationLon)
        writer.ints(table.stationUic)
        writer.nestedInts(table.transfers)
        writer.nestedBytes(table.calendars)
        writer.nestedInts(table.patternStations)
        writer.nestedBytes(table.patternFlags)
        writer.ints(table.tripPattern)
        writer.ints(table.tripCalendar)
        writer.ints(table.tripNumber)
        writer.ints(table.tripLabel)
        writer.bytes(table.tripMode)
        writer.ints(table.tripOffset)
        writer.ints(table.times)
        temporary.outputStream().use { writer.writeTo(it) }
        check(temporary.renameTo(target)) { "Could not save the timetables" }
    }

    /** Null if the file is missing, damaged or in an old format: merging again is then enough. */
    fun read(source: File): RailTimetable? {
        if (!source.exists()) return null
        return try {
            RandomAccessFile(source, "r").use { file ->
                val buffer = file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                if (buffer.getInt() != FORMAT) return null
                val date = buffer.getInt()
                RailTimetable(
                    firstDate = LocalDate.of(date / 10000, date / 100 % 100, date % 100),
                    dayCount = buffer.getInt(),
                    strings = buffer.readStrings(),
                    stationNames = buffer.readStrings(),
                    stationLat = buffer.readFloats(),
                    stationLon = buffer.readFloats(),
                    stationUic = buffer.readInts(),
                    transfers = buffer.readNestedInts(),
                    calendars = buffer.readNestedBytes(),
                    patternStations = buffer.readNestedInts(),
                    patternFlags = buffer.readNestedBytes(),
                    tripPattern = buffer.readInts(),
                    tripCalendar = buffer.readInts(),
                    tripNumber = buffer.readInts(),
                    tripLabel = buffer.readInts(),
                    tripMode = buffer.readBytes(),
                    tripOffset = buffer.readInts(),
                    times = buffer.readInts(),
                )
            }
        } catch (e: RuntimeException) {
            null
        }
    }

    private class Writer {
        private val parts = ArrayList<ByteBuffer>()

        fun int(value: Int) {
            parts += ByteBuffer.allocate(4).putInt(value).apply { flip() }
        }

        fun ints(values: IntArray) {
            int(values.size)
            parts += ByteBuffer.allocate(values.size * 4).apply { asIntBuffer().put(values) }
        }

        fun floats(values: FloatArray) {
            int(values.size)
            parts += ByteBuffer.allocate(values.size * 4).apply { asFloatBuffer().put(values) }
        }

        fun bytes(values: ByteArray) {
            int(values.size)
            parts += ByteBuffer.wrap(values)
        }

        fun strings(values: Array<String>) = nestedBytes(Array(values.size) { values[it].toByteArray() })

        fun nestedInts(values: Array<IntArray>) {
            ints(IntArray(values.size) { values[it].size })
            values.forEach { row -> parts += ByteBuffer.allocate(row.size * 4).apply { asIntBuffer().put(row) } }
        }

        fun nestedBytes(values: Array<ByteArray>) {
            ints(IntArray(values.size) { values[it].size })
            values.forEach { row -> parts += ByteBuffer.wrap(row) }
        }

        fun writeTo(output: java.io.FileOutputStream) {
            parts.forEach { output.channel.write(it.duplicate()) }
        }
    }

    private fun ByteBuffer.readInts(): IntArray {
        val values = IntArray(getInt())
        asIntBuffer().get(values)
        position(position() + values.size * 4)
        return values
    }

    private fun ByteBuffer.readFloats(): FloatArray {
        val values = FloatArray(getInt())
        asFloatBuffer().get(values)
        position(position() + values.size * 4)
        return values
    }

    private fun ByteBuffer.readBytes(): ByteArray = ByteArray(getInt()).also { get(it) }

    private fun ByteBuffer.readStrings(): Array<String> {
        val sizes = readInts()
        return Array(sizes.size) { ByteArray(sizes[it]).also(::get).toString(Charsets.UTF_8) }
    }

    private fun ByteBuffer.readNestedInts(): Array<IntArray> {
        val sizes = readInts()
        return Array(sizes.size) { i ->
            IntArray(sizes[i]).also {
                asIntBuffer().get(it)
                position(position() + it.size * 4)
            }
        }
    }

    private fun ByteBuffer.readNestedBytes(): Array<ByteArray> {
        val sizes = readInts()
        return Array(sizes.size) { ByteArray(sizes[it]).also(::get) }
    }
}
