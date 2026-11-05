package com.scresto.datrain.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.ZoneId

data class RealtimeStop(val uic: String, val arrivalEpoch: Long?, val departureEpoch: Long?)

data class RealtimeTrip(val number: String, val startDate: String, val cancelled: Boolean, val stops: List<RealtimeStop>)

/** Who an alert is for: a train, a station, or a train at that station only. */
data class AlertTarget(val train: String?, val station: String?)

data class ServiceAlert(
    val title: String,
    val description: String,
    val targets: List<AlertTarget>,
    /** Validity periods (start, end) in seconds since 1970; empty if always valid. */
    val periods: List<Pair<Long?, Long?>> = emptyList(),
) {
    fun isActiveAt(epoch: Long): Boolean =
        periods.isEmpty() || periods.any { (start, end) -> (start == null || epoch >= start) && (end == null || epoch <= end) }

    /** A station only counts if the traveller boards or alights there. */
    fun concerns(trainNumbers: List<String>, boarding: String, alighting: String): Boolean =
        targets.any { target ->
            (target.train == null || target.train in trainNumbers) &&
                (target.station == null || target.station == boarding || target.station == alighting) &&
                (target.train != null || target.station != null)
        }
}

/** Alerts for one train: those about the whole train, and those about one of its stations. */
data class TrainAlerts(
    val train: List<ServiceAlert> = emptyList(),
    val byStation: Map<String, List<ServiceAlert>> = emptyMap(),
) {
    companion object {
        fun of(alerts: List<ServiceAlert>, trip: Trip, zone: ZoneId = PARIS): TrainAlerts {
            val numbers = TimetableMerger.numbers(trip.number)
            val epoch = trip.departure.atZone(zone).toEpochSecond()
            val active = alerts.filter { it.isActiveAt(epoch) }
            val byStation = HashMap<String, MutableList<ServiceAlert>>()
            for (alert in active) {
                alert.targets
                    .filter { it.station != null && (it.train == null || it.train in numbers) }
                    .mapNotNull { it.station }
                    .distinct()
                    .forEach { byStation.getOrPut(it) { ArrayList() } += alert }
            }
            return TrainAlerts(
                train = active.filter { alert -> alert.targets.any { it.station == null && it.train in numbers } }
                    .distinctBy { it.title to it.description },
                byStation = byStation,
            )
        }
    }
}

/** The alerts feed weighs about 230 KB compressed: it is shared between screens and reloaded after a few minutes. */
class AlertsCache private constructor(client: OkHttpClient) {
    private val api = GtfsRealtimeApi(client)
    private val mutex = Mutex()
    private var alerts: List<ServiceAlert>? = null
    private var loadedAt = 0L

    suspend fun get(): List<ServiceAlert> = mutex.withLock {
        alerts?.takeIf { System.currentTimeMillis() - loadedAt < MAX_AGE_MILLIS }
            ?: api.alerts().also {
                alerts = it
                loadedAt = System.currentTimeMillis()
            }
    }

    companion object {
        const val MAX_AGE_MILLIS = 5 * 60 * 1000L

        @Volatile private var instance: AlertsCache? = null

        fun get(context: Context): AlertsCache = instance ?: synchronized(this) {
            instance ?: AlertsCache(Http.client(context)).also { instance = it }
        }
    }
}

/** Open SNCF feeds (GTFS-RT, protobuf), read without a dependency: only a few fields are used. */
class GtfsRealtimeApi(private val client: OkHttpClient) {
    suspend fun tripUpdates(): List<RealtimeTrip> = GtfsRealtimeParser.parseTripUpdates(download("sncf-gtfs-rt-trip-updates"))

    suspend fun alerts(): List<ServiceAlert> = GtfsRealtimeParser.parseAlerts(download("sncf-gtfs-rt-service-alerts"))

    private suspend fun download(name: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://proxy.transport.data.gouv.fr/resource/$name")
            .header("User-Agent", "Datrain-Android/1.0")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw NomadeError.Api("HTTP ${response.code}")
                response.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: IOException) {
            throw NomadeError.Network
        }
    }
}

internal object GtfsRealtimeParser {
    private const val CANCELED = 3L
    private val TRAIN_NUMBER = Regex("^OCESN(\\d+)")
    private val STATION_UIC = Regex("(\\d{8})$")
    private val TAG = Regex("<[^>]+>")
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private val BLOCK_END = Regex("(?i)</p>|<br\\s*/?>")

    fun parseTripUpdates(data: ByteArray): List<RealtimeTrip> = readable {
        buildList {
            forEachField(data) { field, value ->
                if (field == 2) (value as? ByteArray)?.let { entity ->
                    forEachField(entity) { entityField, update ->
                        if (entityField == 3) parseTrip(update as ByteArray)?.let(::add)
                    }
                }
            }
        }
    }

    private fun parseTrip(data: ByteArray): RealtimeTrip? {
        var tripId = ""
        var startDate = ""
        var relationship = 0L
        val stops = ArrayList<RealtimeStop>()
        forEachField(data) { field, value ->
            when (field) {
                1 -> forEachField(value as ByteArray) { f, v ->
                    when (f) {
                        1 -> tripId = (v as ByteArray).decodeToString()
                        3 -> startDate = (v as ByteArray).decodeToString()
                        4 -> relationship = v as Long
                    }
                }
                2 -> parseStop(value as ByteArray)?.let(stops::add)
            }
        }
        val number = TRAIN_NUMBER.find(tripId)?.groupValues?.get(1) ?: return null
        return RealtimeTrip(number, startDate, relationship == CANCELED, stops)
    }

    private fun parseStop(data: ByteArray): RealtimeStop? {
        var uic: String? = null
        var arrival: Long? = null
        var departure: Long? = null
        forEachField(data) { field, value ->
            when (field) {
                2 -> arrival = eventTime(value as ByteArray)
                3 -> departure = eventTime(value as ByteArray)
                4 -> uic = STATION_UIC.find((value as ByteArray).decodeToString())?.value
            }
        }
        return uic?.let { RealtimeStop(it, arrival, departure) }
    }

    private fun eventTime(data: ByteArray): Long? {
        var time: Long? = null
        forEachField(data) { field, value -> if (field == 2) time = value as Long }
        return time
    }

    fun parseAlerts(data: ByteArray): List<ServiceAlert> = readable {
        buildList {
            forEachField(data) { field, value ->
                if (field == 2) (value as? ByteArray)?.let { entity ->
                    forEachField(entity) { entityField, alert ->
                        if (entityField == 5) add(parseAlert(alert as ByteArray))
                    }
                }
            }
        }.filter { it.title.isNotEmpty() }
    }

    private fun parseAlert(data: ByteArray): ServiceAlert {
        val targets = ArrayList<AlertTarget>()
        var title = ""
        var description = ""
        val periods = ArrayList<Pair<Long?, Long?>>()
        forEachField(data) { field, value ->
            when (field) {
                1 -> {
                    var start: Long? = null
                    var end: Long? = null
                    forEachField(value as ByteArray) { f, v ->
                        when (f) {
                            1 -> start = v as Long
                            2 -> end = v as Long
                        }
                    }
                    periods += start to end
                }
                5 -> {
                    var train: String? = null
                    var station: String? = null
                    forEachField(value as ByteArray) { f, v ->
                        when (f) {
                            4 -> forEachField(v as ByteArray) { tf, tv ->
                                if (tf == 1) train = TRAIN_NUMBER.find((tv as ByteArray).decodeToString())?.groupValues?.get(1)
                            }
                            5 -> station = STATION_UIC.find((v as ByteArray).decodeToString())?.value
                        }
                    }
                    targets += AlertTarget(train, station)
                }
                10 -> title = cleanHtml(frenchText(value as ByteArray))
                11 -> description = cleanHtml(frenchText(value as ByteArray))
            }
        }
        return ServiceAlert(title, description, targets, periods)
    }

    private fun frenchText(data: ByteArray): String {
        var result = ""
        forEachField(data) { field, value ->
            if (field == 1) {
                var text = ""
                var language = ""
                forEachField(value as ByteArray) { f, v ->
                    when (f) {
                        1 -> text = (v as ByteArray).decodeToString()
                        2 -> language = (v as ByteArray).decodeToString()
                    }
                }
                if (language == "fr" || result.isEmpty()) result = text
            }
        }
        return result
    }

    internal fun cleanHtml(html: String): String =
        TAG.replace(BLOCK_END.replace(html, "\n"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&#39;", "'").replace("&quot;", "\"")
            .lineSequence().map { SPACES.replace(it, " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    /** Long for an integer, ByteArray for a string or message; fixed numbers are ignored. */
    private inline fun forEachField(data: ByteArray, block: (Int, Any) -> Unit) {
        var i = 0
        while (i < data.size) {
            var key = 0L
            var shift = 0
            while (true) {
                val b = data[i++].toInt()
                key = key or ((b and 127).toLong() shl shift)
                shift += 7
                if (b and 128 == 0) break
            }
            val field = (key shr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> {
                    var value = 0L
                    shift = 0
                    while (true) {
                        val b = data[i++].toInt()
                        value = value or ((b and 127).toLong() shl shift)
                        shift += 7
                        if (b and 128 == 0) break
                    }
                    block(field, value)
                }
                1 -> i += 8
                2 -> {
                    var length = 0
                    shift = 0
                    while (true) {
                        val b = data[i++].toInt()
                        length = length or ((b and 127) shl shift)
                        shift += 7
                        if (b and 128 == 0) break
                    }
                    block(field, data.copyOfRange(i, i + length))
                    i += length
                }
                5 -> i += 4
                else -> throw IllegalArgumentException("Unknown field type")
            }
        }
    }

    private inline fun <T> readable(block: () -> T): T = try {
        block()
    } catch (e: IndexOutOfBoundsException) {
        throw NomadeError.Api("unreadable response")
    } catch (e: IllegalArgumentException) {
        throw NomadeError.Api("unreadable response")
    } catch (e: ClassCastException) {
        throw NomadeError.Api("unreadable response")
    }
}
