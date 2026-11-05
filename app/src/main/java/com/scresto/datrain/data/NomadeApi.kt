package com.scresto.datrain.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.ZoneId

val PARIS: ZoneId = ZoneId.of("Europe/Paris")

class NomadeApi(private val client: OkHttpClient) {

    suspend fun liveFeed(): LiveFeed = get("sncf.json", LiveParser::parseFeed)

    /** A single station, about 3 hours ahead: far lighter than the whole network. */
    suspend fun board(uic: String): Board = get("gare/$uic.json", LiveParser::parseBoard)

    /** Tracks and stations rarely change: a copy less than 30 days old is enough. */
    suspend fun buildRoute(
        stops: List<LiveStop>,
        extraStations: Map<String, StationInfo> = emptyMap(),
        allStops: List<LiveStop> = stops,
    ): TrainRoute {
        val stations = get("donnees/v1/desserte-gares.csv", LiveParser::parseStations, MAX_STALE)
        val wanted = stops.zipWithNext { a, b -> a.uic to b.uic }.toSet()
        val tracks = get(
            "donnees/v1/traces-ferroviaires.geojson",
            { code, body -> LiveParser.parseTracks(code, body, wanted) },
            MAX_STALE,
        )
        return TrainRoute.build(stops, extraStations + stations, tracks, allStops)
    }

    private suspend fun <T> get(
        path: String,
        parse: (Int, String) -> T,
        cacheControl: String? = null,
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.trainnomade.fr/$path")
            .header("User-Agent", "Datrain-Android/1.0")
            .apply { cacheControl?.let { header("Cache-Control", it) } }
            .build()
        try {
            client.newCall(request).execute().use { response ->
                parse(response.code, response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw NomadeError.Network
        }
    }

    private companion object {
        const val MAX_STALE = "max-stale=2592000"
    }
}
