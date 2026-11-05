package com.scresto.datrain.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

object LiveParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseFeed(httpCode: Int, body: String): LiveFeed {
        check(httpCode)
        return readable {
            val dto = json.decodeFromString<FeedDto>(body)
            val day = LocalDate.parse(dto.jour)
            LiveFeed(
                collectedAt = OffsetDateTime.parse(dto.collecteLe),
                staleAfter = OffsetDateTime.parse(dto.perimeApres),
                trains = dto.trains.map { (key, train) ->
                    LiveTrain(
                        key = key,
                        // A number seen twice in a day is written "6611/2".
                        number = key.substringBefore('/'),
                        serviceDate = train.j?.let(LocalDate::parse) ?: day,
                        cancelled = train.an == 1,
                        hasRealtime = train.nm != 1,
                        product = train.p,
                        destination = train.dn,
                        origin = train.on,
                        stops = train.ar.map {
                            LiveStop(
                                uic = it.s,
                                arrivalSeconds = it.a,
                                departureSeconds = it.d,
                                arrivalDelaySeconds = it.ra,
                                departureDelaySeconds = it.rd,
                                platform = it.v,
                                passed = it.p == 1,
                                removed = it.x == 1,
                            )
                        },
                    )
                },
            )
        }
    }

    fun parseBoard(httpCode: Int, body: String): Board {
        check(httpCode)
        return readable {
            val dto = json.decodeFromString<BoardDto>(body)
            Board(
                day = LocalDate.parse(dto.jour),
                staleAfter = OffsetDateTime.parse(dto.perimeApres),
                passages = dto.passages.map {
                    Passage(
                        // A number seen twice in a day is written "6611/2".
                        number = it.n.substringBefore('/'),
                        arrivalSeconds = it.a,
                        departureSeconds = it.d,
                        arrivalDelaySeconds = it.ra,
                        departureDelaySeconds = it.rd,
                        platform = it.v,
                        cancelled = it.an == 1 || it.x == 1,
                    )
                },
            )
        }
    }

    fun parseTracks(httpCode: Int, body: String, wanted: Set<Pair<String, String>>): List<Track> {
        check(httpCode)
        return readable {
            json.decodeFromString<TracksDto>(body).features
                .filter { f ->
                    val a = f.properties.codeUicA
                    val b = f.properties.codeUicB
                    (a to b) in wanted || (b to a) in wanted
                }
                .map { f ->
                    // GeoJSON: longitude first.
                    Track(f.properties.codeUicA, f.properties.codeUicB, f.geometry.coordinates.map { LatLon(it[1], it[0]) })
                }
        }
    }

    /** One entry per UIC code, including a station's secondary codes. */
    fun parseStations(httpCode: Int, body: String): Map<String, StationInfo> {
        check(httpCode)
        return readable {
            val lines = body.lineSequence().filter { it.isNotBlank() }.toList()
            val header = splitCsv(lines.firstOrNull() ?: throw NomadeError.Api("unreadable response"))
            val code = header.indexOf("code_uic")
            val others = header.indexOf("autres_codes_uic")
            val name = header.indexOf("nom")
            val lat = header.indexOf("latitude")
            val lon = header.indexOf("longitude")
            if (listOf(code, others, name, lat, lon).any { it < 0 }) throw NomadeError.Api("unreadable response")
            buildMap {
                for (line in lines.drop(1)) {
                    val cells = splitCsv(line)
                    val latitude = cells.getOrNull(lat)?.toDoubleOrNull()
                    val longitude = cells.getOrNull(lon)?.toDoubleOrNull()
                    val info = StationInfo(
                        name = cells.getOrElse(name) { "" },
                        position = if (latitude != null && longitude != null) LatLon(latitude, longitude) else null,
                    )
                    val otherCodes = Regex("\\d+").findAll(cells.getOrElse(others) { "" }).map { it.value }
                    (sequenceOf(cells.getOrElse(code) { "" }) + otherCodes)
                        .filter { it.isNotEmpty() }
                        .forEach { put(it, info) }
                }
            }
        }
    }

    internal fun splitCsv(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && line.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    cells += cell.toString()
                    cell.clear()
                }
                else -> cell.append(c)
            }
            i++
        }
        cells += cell.toString()
        return cells
    }

    private fun check(code: Int) {
        when {
            code == 429 -> throw NomadeError.RateLimited
            code !in 200..299 -> throw NomadeError.Api("HTTP $code")
        }
    }

    private inline fun <T> readable(block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw NomadeError.Api("unreadable response")
    } catch (e: IllegalArgumentException) {
        throw NomadeError.Api("unreadable response")
    } catch (e: IndexOutOfBoundsException) {
        throw NomadeError.Api("unreadable response")
    } catch (e: DateTimeParseException) {
        throw NomadeError.Api("unreadable response")
    }
}

data class Passage(
    val number: String,
    val arrivalSeconds: Int?,
    val departureSeconds: Int?,
    val arrivalDelaySeconds: Int,
    val departureDelaySeconds: Int,
    val platform: String?,
    val cancelled: Boolean,
)

data class Board(val day: LocalDate, val staleAfter: OffsetDateTime, val passages: List<Passage>)

@Serializable
private data class BoardDto(
    val jour: String,
    @SerialName("perime_apres") val perimeApres: String,
    val passages: List<PassageDto> = emptyList(),
)

@Serializable
private data class PassageDto(
    val n: String,
    val a: Int? = null,
    val d: Int? = null,
    val ra: Int = 0,
    val rd: Int = 0,
    val v: String? = null,
    val an: Int = 0,
    val x: Int = 0,
)

@Serializable
private data class FeedDto(
    val jour: String,
    @SerialName("collecte_le") val collecteLe: String,
    @SerialName("perime_apres") val perimeApres: String,
    val trains: Map<String, TrainDto> = emptyMap(),
)

@Serializable
private data class TrainDto(
    val j: String? = null,
    val p: String? = null,
    val dn: String? = null,
    val on: String? = null,
    val an: Int = 0,
    val nm: Int = 0,
    val ar: List<StopDto> = emptyList(),
)

@Serializable
private data class StopDto(
    val s: String,
    val a: Int? = null,
    val d: Int? = null,
    val ra: Int = 0,
    val rd: Int = 0,
    val v: String? = null,
    val p: Int = 0,
    val x: Int = 0,
)

@Serializable
private data class TracksDto(val features: List<FeatureDto> = emptyList())

@Serializable
private data class FeatureDto(val properties: PropertiesDto, val geometry: GeometryDto)

@Serializable
private data class PropertiesDto(
    @SerialName("code_uic_a") val codeUicA: String,
    @SerialName("code_uic_b") val codeUicB: String,
)

@Serializable
private data class GeometryDto(val coordinates: List<List<Double>>)
