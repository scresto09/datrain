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
    /** When the source last updated this train, in epoch seconds; 0 if unknown. */
    val updatedAt: Long = 0,
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
    /** Past [staleAfter], its delays are old: it counts as no feed. */
    fun isFresh(now: OffsetDateTime = OffsetDateTime.now(PARIS)): Boolean = now < staleAfter

    /**
     * After midnight, the source copies a train still running into the new day, its times counted from that
     * new midnight, and updates only the copy. The most recently updated of the two is the one that counts.
     */
    fun find(trip: Trip): LiveTrain? {
        val numbers = TimetableMerger.numbers(trip.number)
        val seconds = java.time.Duration.between(trip.serviceDate.atStartOfDay(), trip.departure).seconds.toInt()
        // Leftover entries are marked removed: they say nothing about when the train runs.
        fun matching(candidates: List<LiveTrain>, departure: Int): LiveTrain? =
            candidates.firstOrNull { train -> train.stops.any { !it.removed && it.departureSeconds == departure } }
                // Time differs from the planned one: the number is enough if unambiguous.
                ?: candidates.singleOrNull()
        val ofNumber = trains.filter { it.number in numbers }
        // A copy of the day before is never this day's train, even with the same number and time.
        val same = matching(ofNumber.filter { it.serviceDate == trip.serviceDate && !it.isCopy }, seconds)
        val copy = matching(ofNumber.filter { it.serviceDate == trip.serviceDate.plusDays(1) && it.isCopy }, seconds - DAY)
            ?.backTo(trip.serviceDate)
        return listOfNotNull(same, copy).maxByOrNull { it.updatedAt }
    }

    /**
     * The copy, in the day it left, its times counted from that day. Its leftover entries of the old day,
     * marked removed with times near the old midnight, are dropped: real ones are close to the new midnight.
     */
    private fun LiveTrain.backTo(day: java.time.LocalDate): LiveTrain = copy(
        serviceDate = day,
        stops = stops
            .filterNot { it.removed && (it.arrivalSeconds ?: it.departureSeconds ?: 0) >= DAY / 2 }
            .map { it.copy(arrivalSeconds = it.arrivalSeconds?.plus(DAY), departureSeconds = it.departureSeconds?.plus(DAY)) },
    )

    /** A train of the day before copied into this day: it has times before this day's midnight. */
    private val LiveTrain.isCopy: Boolean
        get() = stops.any { !it.removed && (it.departureSeconds ?: it.arrivalSeconds ?: 0) < 0 }

    companion object {
        /** How far ahead the live feed knows trains, and how long it keeps them: about 6 hours. */
        val HORIZON: java.time.Duration = java.time.Duration.ofHours(6)
        private const val DAY = 86400
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
