package de.universam.victron.data.model

import kotlin.math.abs

/**
 * One metric's trend over the whole time the app has been running, decimated to a fixed number of
 * points so that neither memory nor redraw cost grows with the session.
 *
 * The window starts at the first reading and only ever grows: when [values] fills up, adjacent
 * buckets are merged and [samplesPerBucket] doubles. A merged bucket keeps the most *extreme* of
 * its samples, never the average — a 40 A spike that lasted a single reading is still 40 A after
 * six doublings. "Extreme" means the sample furthest from zero with its sign kept, which is plain
 * `max` for every metric that cannot go negative and the right answer for battery current, where a
 * discharge is as much of a spike as a charge.
 *
 * [peak] is therefore exact rather than approximate: merging preserves extremes, so the peak of the
 * buckets is the peak of every reading ever appended. Swap the merge for an average and that
 * guarantee — and the gauge tick that depends on it — quietly stops being true.
 *
 * Immutable: [append] returns a new instance, so the whole buffer is a value the UI can hold.
 */
public data class MetricSeries(
    /** One value per committed bucket, oldest first; each the extreme of its bucket's samples. */
    public val values: List<Float> = emptyList(),
    /** How many readings each committed bucket covers. Doubles every time [values] fills up. */
    public val samplesPerBucket: Int = 1,
    /** Running extreme of the bucket still filling up; `null` when nothing is pending. */
    public val pendingPeak: Float? = null,
    /** How many readings the pending bucket already holds, always `< samplesPerBucket`. */
    public val pendingCount: Int = 0,
    /** Wall clock of the oldest sample still in the window; `null` while empty. */
    public val firstSampleMillis: Long? = null,
    /** Wall clock of the newest sample; `null` while empty. */
    public val lastSampleMillis: Long? = null,
    /**
     * Committed buckets kept before merging. Must be even, so a merge always halves cleanly and
     * every committed bucket covers exactly [samplesPerBucket] readings. Overridden only by tests
     * and previews.
     */
    public val capacity: Int = CAPACITY,
) {
    init {
        require(capacity >= 2 && capacity % 2 == 0) { "capacity must be even and >= 2, was $capacity" }
    }

    /**
     * What a sparkline draws: the committed buckets plus the bucket still filling, so the tip keeps
     * moving on every reading even once one bucket spans a hundred of them.
     */
    public val points: List<Float> = pendingPeak?.let { values + it } ?: values

    /** The most extreme sample in the window, sign kept. `null` while empty. */
    public val peak: Float? = points.maxByOrNull { abs(it) }

    /**
     * How far back the window reaches, oldest sample to newest. This is *not* how much time the
     * curve covers: scans are bounded to a visible screen, so a series can span six hours of wall
     * clock made up of two five-minute bursts. It answers "how old is the left edge", which is what
     * the label next to a trend claims.
     */
    public val spanMillis: Long =
        if (firstSampleMillis == null || lastSampleMillis == null) {
            0L
        } else {
            (lastSampleMillis - firstSampleMillis).coerceAtLeast(0L)
        }

    public fun append(value: Float, atMillis: Long): MetricSeries {
        val bucketPeak = pendingPeak?.let { moreExtreme(it, value) } ?: value
        val count = pendingCount + 1
        val first = firstSampleMillis ?: atMillis

        // Still filling the current bucket.
        if (count < samplesPerBucket) {
            return copy(
                pendingPeak = bucketPeak,
                pendingCount = count,
                firstSampleMillis = first,
                lastSampleMillis = atMillis,
            )
        }

        // Commit, then decimate if that filled the series. Because the check runs on every commit
        // the size can only ever *reach* capacity, never pass it, so `chunked(2)` always halves an
        // even list and no half-filled bucket is left behind pretending to be complete — which is
        // what keeps `values.size * samplesPerBucket + pendingCount` equal to the reading count.
        var committed = values + bucketPeak
        var perBucket = samplesPerBucket
        while (committed.size >= capacity) {
            committed = committed.chunked(2) { pair -> pair.reduce(::moreExtreme) }
            perBucket *= 2
        }
        return copy(
            values = committed,
            samplesPerBucket = perBucket,
            pendingPeak = null,
            pendingCount = 0,
            firstSampleMillis = first,
            lastSampleMillis = atMillis,
        )
    }

    /** [append] when the reading carries this metric, unchanged when the device did not report it. */
    internal fun appended(value: Float?, atMillis: Long): MetricSeries =
        if (value == null) this else append(value, atMillis)

    /** An empty series with this one's [capacity], for the day rollover. */
    internal fun cleared(): MetricSeries = MetricSeries(capacity = capacity)

    public companion object {
        /**
         * Committed buckets kept per metric. The observable size swings between half of this and all
         * of it, so the average sparkline is ~90 points — finer than the pixels a tile-sized
         * sparkline gets, and within 2x of the 60-point path this replaced. Must be even.
         */
        public const val CAPACITY: Int = 120

        /** Builds a series from ready-made samples, for previews and tests. */
        public fun of(
            samples: List<Float>,
            startMillis: Long = 0L,
            stepMillis: Long = 1_000L,
            capacity: Int = CAPACITY,
        ): MetricSeries = samples.foldIndexed(MetricSeries(capacity = capacity)) { i, series, value ->
            series.append(value, startMillis + i * stepMillis)
        }
    }
}

/** Of two samples, the one further from zero — plain `max` unless the metric can go negative. */
private fun moreExtreme(a: Float, b: Float): Float = if (abs(b) > abs(a)) b else a
