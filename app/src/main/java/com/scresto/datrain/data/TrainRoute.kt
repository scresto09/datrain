package com.scresto.datrain.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

data class StationInfo(val name: String, val position: LatLon?)

data class Track(val uicA: String, val uicB: String, val points: List<LatLon>)

data class RouteStop(val name: String, val position: LatLon?)

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
data class TrainRoute(val stops: List<RouteStop>, val segments: List<RouteSegment?>) {

    /** Null when the position is unknown: none is made up. */
    fun positionOf(progress: TrainProgress): LatLon? = when (progress) {
        TrainProgress.NotStarted -> stops.firstOrNull()?.position
        is TrainProgress.AtStop -> stops.getOrNull(progress.index)?.position
        is TrainProgress.Between -> segments.getOrNull(progress.from)?.pointAt(progress.fraction)
        TrainProgress.Arrived -> stops.lastOrNull()?.position
    }

    companion object {
        fun build(stops: List<LiveStop>, stations: Map<String, StationInfo>, tracks: List<Track>): TrainRoute {
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
            return TrainRoute(routeStops, segments)
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
