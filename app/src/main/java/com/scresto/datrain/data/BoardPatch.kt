package com.scresto.datrain.data

/**
 * Brings a train from the full feed up to date with one station's board, which weighs about
 * 1.4 KB instead of 270 KB. Later stops shift by the same change of delay until the next full read.
 */
object BoardPatch {
    /** The board's passage for [stop] of [train]: same number, same planned time, or the only one. */
    fun find(board: Board, train: LiveTrain, stop: LiveStop): Passage? {
        val numbers = TimetableMerger.numbers(train.number)
        val candidates = board.passages.filter { it.number in numbers }
        return candidates.firstOrNull { passage ->
            (stop.departureSeconds != null && passage.departureSeconds == stop.departureSeconds) ||
                (stop.arrivalSeconds != null && passage.arrivalSeconds == stop.arrivalSeconds)
        } ?: candidates.singleOrNull()
    }

    fun apply(train: LiveTrain, index: Int, passage: Passage): LiveTrain {
        val old = train.stops[index]
        val change = if (old.departureSeconds != null) {
            passage.departureDelaySeconds - old.departureDelaySeconds
        } else {
            passage.arrivalDelaySeconds - old.arrivalDelaySeconds
        }
        val stops = train.stops.mapIndexed { i, stop ->
            when {
                i == index -> stop.copy(
                    arrivalDelaySeconds = passage.arrivalDelaySeconds,
                    departureDelaySeconds = passage.departureDelaySeconds,
                    platform = passage.platform ?: stop.platform,
                )
                i > index -> stop.copy(
                    arrivalDelaySeconds = (stop.arrivalDelaySeconds + change).coerceAtLeast(0),
                    departureDelaySeconds = (stop.departureDelaySeconds + change).coerceAtLeast(0),
                )
                else -> stop
            }
        }
        return train.copy(stops = stops)
    }
}
