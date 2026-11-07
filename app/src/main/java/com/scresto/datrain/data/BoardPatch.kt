package com.scresto.datrain.data

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Brings a train from the full feed up to date with one station's board, which weighs about
 * 1.4 KB instead of 270 KB. Later stops shift by the same change of delay until the next full read.
 */
object BoardPatch {
    /**
     * The board's passage of train [number] at a stop planned at [departure] or [arrival], in seconds from
     * midnight of [serviceDate]: same number and planned time, or the only passage of that number.
     * After midnight, a train of the day before is on the new day's board, its times counted from that new
     * midnight. Null when the board cannot tell: out of date, another day, or without the train.
     * The one way to read a board, for the map and for the background check.
     */
    fun find(
        board: Board,
        number: String,
        serviceDate: LocalDate,
        departure: Int?,
        arrival: Int?,
        now: OffsetDateTime,
    ): Passage? {
        if (now >= board.staleAfter) return null
        val days = ChronoUnit.DAYS.between(serviceDate, board.day)
        if (days !in 0..1) return null
        val shift = (days * DAY).toInt()
        val numbers = TimetableMerger.numbers(number)
        val candidates = board.passages.filter { it.number in numbers }
        return candidates.firstOrNull { passage ->
            (departure != null && passage.departureSeconds == departure - shift) ||
                (arrival != null && passage.arrivalSeconds == arrival - shift)
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

    private const val DAY = 86400
}
