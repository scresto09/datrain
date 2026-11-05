package com.scresto.datrain.data

enum class StopStatus { PASSED, NEXT, AHEAD, REMOVED }

/** One line of the stop list. Times are seconds from midnight of the service day. */
data class StopRow(
    val uic: String,
    val name: String,
    /** Position among served stops, the index used by the route; null if the stop is removed. */
    val activeIndex: Int?,
    val status: StopStatus,
    val isDeparture: Boolean,
    val planned: Int?,
    /** Null unless it differs from [planned] by at least a minute on the clock. */
    val estimated: Int?,
    val platform: String?,
    val isBoarding: Boolean,
    val isAlighting: Boolean,
)

object StopRows {
    fun build(train: LiveTrain, names: Map<String, String>, progress: TrainProgress?, trip: Trip): List<StopRow> {
        var active = -1
        return train.stops.map { stop ->
            val index = if (stop.removed) null else ++active
            // Where the traveller boards, the time that matters is the departure.
            val isDeparture = stop.arrivalSeconds == null || (stop.uic == trip.fromKey && stop.departureSeconds != null)
            val planned = if (isDeparture) stop.departureSeconds else stop.arrivalSeconds
            val estimated = if (isDeparture) stop.estimatedDeparture else stop.estimatedArrival
            StopRow(
                uic = stop.uic,
                name = names[stop.uic] ?: "Gare ${stop.uic}",
                activeIndex = index,
                status = if (index == null) StopStatus.REMOVED else status(index, progress),
                isDeparture = isDeparture,
                planned = planned,
                estimated = estimated?.takeIf { train.hasRealtime && it / 60 != (planned ?: it) / 60 },
                platform = stop.platform,
                isBoarding = stop.uic == trip.fromKey,
                isAlighting = stop.uic == trip.toKey,
            )
        }
    }

    /** The train's place in the list: 2.5 is halfway between rows 2 and 3. Null when unknown. */
    fun trainPosition(rows: List<StopRow>, progress: TrainProgress?): Double? {
        fun row(active: Int) = rows.indexOfFirst { it.activeIndex == active }.takeIf { it >= 0 }
        return when (progress) {
            null -> null
            TrainProgress.NotStarted -> row(0)?.toDouble()
            is TrainProgress.AtStop -> row(progress.index)?.toDouble()
            is TrainProgress.Between -> {
                val from = row(progress.from) ?: return null
                val to = row(progress.to) ?: return null
                from + (to - from) * progress.fraction
            }
            TrainProgress.Arrived -> rows.indexOfLast { it.activeIndex != null }.takeIf { it >= 0 }?.toDouble()
        }
    }

    private fun status(index: Int, progress: TrainProgress?): StopStatus {
        val next = when (progress) {
            null -> return StopStatus.AHEAD
            TrainProgress.NotStarted -> 0
            is TrainProgress.AtStop -> progress.index
            is TrainProgress.Between -> progress.to
            TrainProgress.Arrived -> return StopStatus.PASSED
        }
        return when {
            index < next -> StopStatus.PASSED
            index == next -> StopStatus.NEXT
            else -> StopStatus.AHEAD
        }
    }
}
