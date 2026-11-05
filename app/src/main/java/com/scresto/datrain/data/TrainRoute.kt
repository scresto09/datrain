package com.scresto.datrain.data

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class LatLon(val lat: Double, val lon: Double)

@Serializable
data class StationInfo(val name: String, val position: LatLon?)

data class Track(val uicA: String, val uicB: String, val points: List<LatLon>)

/** Where the map opens, before the exact route is loaded. */
data class InitialMapView(val center: LatLon, val zoom: Double) {
    companion object {
        private val FRANCE = InitialMapView(LatLon(46.6, 2.4), 4.5)

        fun around(points: List<LatLon>): InitialMapView {
            if (points.isEmpty()) return FRANCE
            val minLat = points.minOf { it.lat }
            val maxLat = points.maxOf { it.lat }
            val minLon = points.minOf { it.lon }
            val maxLon = points.maxOf { it.lon }
            val span = maxOf(maxLat - minLat, maxLon - minLon).coerceAtLeast(0.02)
            // Each zoom level halves the visible degrees; a phone sees about 360 degrees at level 0.
            val zoom = (log2(360.0 / span) - 1.0).coerceIn(3.0, 13.0)
            return InitialMapView(LatLon((minLat + maxLat) / 2, (minLon + maxLon) / 2), zoom)
        }
    }
}

data class RouteStop(val name: String, val position: LatLon?, val uic: String = "")

/** [isRealTrack] false: straight line between two stations, for lack of a known track. */
data class RouteSegment(val points: List<LatLon>, val isRealTrack: Boolean) {
    private val cumulative: DoubleArray = DoubleArray(points.size).also { sums ->
        for (i in 1 until points.size) sums[i] = sums[i - 1] + distanceMeters(points[i - 1], points[i])
    }

    fun pointAt(fraction: Double): LatLon {
        val target = cumulative.last() * fraction.coerceIn(0.0, 1.0)
        val end = cumulative.indexOfFirst { it >= target }.coerceAtLeast(1)
        val length = cumulative[end] - cumulative[end - 1]
        if (length <= 0.0) return points[end]
        val ratio = (target - cumulative[end - 1]) / length
        val a = points[end - 1]
        val b = points[end]
        return LatLon(a.lat + (b.lat - a.lat) * ratio, a.lon + (b.lon - a.lon) * ratio)
    }
}

/** Segment i links stops i and i + 1; null if a station's coordinates are missing. */
data class TrainRoute(
    val stops: List<RouteStop>,
    val segments: List<RouteSegment?>,
    val stationNames: Map<String, String> = emptyMap(),
    /** Every stop this route was built for, removed ones included. */
    val knownStops: Set<String> = emptySet(),
) {
    /** False when the train's stops have changed since: the route must be built again. */
    fun fits(train: LiveTrain): Boolean =
        stops.map { it.uic } == train.activeStops.map { it.uic } && train.stops.all { it.uic in knownStops }


    /** Null when the position is unknown: none is made up. */
    fun positionOf(progress: TrainProgress): LatLon? = when (progress) {
        TrainProgress.NotStarted -> stops.firstOrNull()?.position
        is TrainProgress.AtStop -> stops.getOrNull(progress.index)?.position
        is TrainProgress.Between -> segments.getOrNull(progress.from)?.pointAt(progress.fraction)
        TrainProgress.Arrived -> stops.lastOrNull()?.position
    }

    companion object {
        /** [allStops] also lists removed stops, which are not on the route but keep a name. */
        fun build(
            stops: List<LiveStop>,
            stations: Map<String, StationInfo>,
            tracks: List<Track>,
            allStops: List<LiveStop> = stops,
        ): TrainRoute {
            val byPair = tracks.associateBy { it.uicA to it.uicB }
            val trackPoints = stops.zipWithNext { a, b ->
                byPair[a.uic to b.uic]?.points ?: byPair[b.uic to a.uic]?.points?.reversed()
            }
            val routeStops = stops.mapIndexed { i, stop ->
                val info = stations[stop.uic]
                RouteStop(
                    name = info?.name ?: "Gare ${stop.uic}",
                    // A station missing from the list can still be placed by the end of a track.
                    position = info?.position
                        ?: trackPoints.getOrNull(i)?.firstOrNull()
                        ?: trackPoints.getOrNull(i - 1)?.lastOrNull(),
                    uic = stop.uic,
                )
            }
            val segments = trackPoints.mapIndexed { i, points ->
                val a = routeStops[i].position
                val b = routeStops[i + 1].position
                when {
                    points != null && points.size >= 2 -> RouteSegment(points, isRealTrack = true)
                    a != null && b != null -> RouteSegment(listOf(a, b), isRealTrack = false)
                    else -> null
                }
            }
            val names = allStops.mapNotNull { stop -> stations[stop.uic]?.let { stop.uic to it.name } }.toMap()
            return TrainRoute(routeStops, segments, names, allStops.map { it.uic }.toSet())
        }

        private fun Double.radians() = Math.toRadians(this)

        internal fun distanceMeters(a: LatLon, b: LatLon): Double {
            val dLat = (b.lat - a.lat).radians()
            val dLon = (b.lon - a.lon).radians()
            val h = sin(dLat / 2).pow(2) + cos(a.lat.radians()) * cos(b.lat.radians()) * sin(dLon / 2).pow(2)
            return 2 * 6_371_000 * asin(sqrt(h))
        }
    }
}

private fun distanceMeters(a: LatLon, b: LatLon) = TrainRoute.distanceMeters(a, b)
