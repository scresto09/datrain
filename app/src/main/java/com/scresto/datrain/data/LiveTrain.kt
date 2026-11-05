package com.scresto.datrain.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime

@Serializable
data class LiveStop(
    val uic: String,
    val arrivalSeconds: Int?,
    val departureSeconds: Int?,
    val arrivalDelaySeconds: Int = 0,
    val departureDelaySeconds: Int = 0,
    val platform: String? = null,
    val passed: Boolean = false,
    val removed: Boolean = false,
) {
    val estimatedArrival: Int? get() = arrivalSeconds?.plus(arrivalDelaySeconds)
    val estimatedDeparture: Int? get() = departureSeconds?.plus(departureDelaySeconds)
}

/** Stop times are seconds from midnight of [serviceDate], Paris time. */
data class LiveTrain(
    val key: String,
    val number: String,
    val serviceDate: LocalDate,
    val cancelled: Boolean,
    val hasRealtime: Boolean,
    val stops: List<LiveStop>,
    val product: String? = null,
    val destination: String? = null,
    val origin: String? = null,
) {
    /** A removed stop is not served: the train goes straight to the next one. */
    val activeStops: List<LiveStop> get() = stops.filterNot { it.removed }

    /**
     * What the feed only shows as removed stops. No open feed gives these as messages,
     * though SNCF Connect displays them: they are worked out here.
     */
    fun serviceNotes(): List<String> {
        if (cancelled) return emptyList()
        val first = stops.indexOfFirst { !it.removed }
        val last = stops.indexOfLast { !it.removed }
        if (first < 0) return emptyList()
        return buildList {
            if (first > 0) add(origin?.let { "Origine exceptionnelle : $it" } ?: "Origine exceptionnelle")
            if (last < stops.lastIndex) add(destination?.let { "Terminus exceptionnel : $it" } ?: "Terminus exceptionnel")
            val skipped = stops.subList(first, last + 1).count { it.removed }
            if (skipped == 1) add("1 arrêt supprimé sur le parcours")
            if (skipped > 1) add("$skipped arrêts supprimés sur le parcours")
        }
    }
}

data class LiveFeed(val collectedAt: OffsetDateTime, val staleAfter: OffsetDateTime, val trains: List<LiveTrain>) {
    fun find(trip: Trip): LiveTrain? {
        val numbers = TimetableMerger.numbers(trip.number)
        val candidates = trains.filter { it.number in numbers && it.serviceDate == trip.serviceDate }
        val seconds = java.time.Duration.between(trip.serviceDate.atStartOfDay(), trip.departure).seconds.toInt()
        return candidates.firstOrNull { train -> train.stops.any { it.departureSeconds == seconds } }
            // Time differs from the planned one: the number is enough if unambiguous.
            ?: candidates.singleOrNull()
    }
}

sealed interface TrainProgress {
    data object NotStarted : TrainProgress
    data class AtStop(val index: Int) : TrainProgress
    data class Between(val from: Int, val to: Int, val fraction: Double) : TrainProgress
    data object Arrived : TrainProgress
}

object ProgressCalculator {
    /**
     * Estimates where the train is at [now] (seconds from midnight of the service day),
     * from times and delays, assuming constant speed between two stops.
     * [stops] must only contain served stops.
     */
    fun compute(stops: List<LiveStop>, now: Int): TrainProgress {
        if (stops.isEmpty()) return TrainProgress.NotStarted
        val byClock = fromClock(stops, now)
        // A confirmed passage is a fact: the estimate must not place the train before it.
        // The reverse does not hold: many passed stops are never marked as confirmed.
        val lastPassed = stops.indexOfLast { it.passed }
        return when {
            lastPassed < 0 -> byClock
            lastPassed == stops.lastIndex -> TrainProgress.Arrived
            rank(byClock, stops.size) < lastPassed -> TrainProgress.AtStop(lastPassed)
            else -> byClock
        }
    }

    private fun fromClock(stops: List<LiveStop>, now: Int): TrainProgress {
        val firstDeparture = stops.first().estimatedDeparture ?: stops.first().estimatedArrival ?: return TrainProgress.NotStarted
        if (now < firstDeparture) return TrainProgress.NotStarted
        var previousDeparture = firstDeparture
        for (i in stops.indices) {
            val stop = stops[i]
            val arrival = stop.estimatedArrival ?: previousDeparture
            if (i > 0 && now < arrival) {
                val duration = (arrival - previousDeparture).coerceAtLeast(1)
                val fraction = ((now - previousDeparture).toDouble() / duration).coerceIn(0.0, 1.0)
                return TrainProgress.Between(i - 1, i, fraction)
            }
            if (i == stops.lastIndex) return TrainProgress.Arrived
            val departure = (stop.estimatedDeparture ?: arrival).coerceAtLeast(arrival)
            if (now < departure) return TrainProgress.AtStop(i)
            previousDeparture = departure
        }
        return TrainProgress.Arrived
    }

    private fun rank(progress: TrainProgress, size: Int): Double = when (progress) {
        TrainProgress.NotStarted -> -1.0
        is TrainProgress.AtStop -> progress.index.toDouble()
        is TrainProgress.Between -> progress.from + 0.5
        TrainProgress.Arrived -> size.toDouble()
    }
}
