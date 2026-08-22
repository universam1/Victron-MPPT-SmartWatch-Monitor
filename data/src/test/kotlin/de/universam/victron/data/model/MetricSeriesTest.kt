package de.universam.victron.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

/**
 * The decimating trend buffer. Built with a tiny [MetricSeries.capacity] so a handful of appends
 * exercises several merges instead of the ~720 the production capacity would need.
 */
class MetricSeriesTest {

    private fun series(capacity: Int = 4) = MetricSeries(capacity = capacity)

    private fun MetricSeries.appendAll(values: List<Float>, stepMillis: Long = 1_000L): MetricSeries =
        values.foldIndexed(this) { i, acc, v -> acc.append(v, i * stepMillis) }

    @Test
    fun `grows far past the old sixty entry cap without exceeding capacity`() {
        val s = series(capacity = 8).appendAll((1..1000).map { it.toFloat() })

        assertTrue(s.points.size <= 8, "points were ${s.points.size}")
        assertTrue(s.values.size < 8, "committed buckets were ${s.values.size}")
        // The window still reaches back to the very first reading, which is the whole point.
        assertEquals(0L, s.firstSampleMillis)
        assertEquals(999_000L, s.lastSampleMillis)
    }

    @Test
    fun `every append keeps buckets times samplesPerBucket plus pending equal to the reading count`() {
        var s = series(capacity = 4)
        for (n in 1..3 * 4 * 4) {
            s = s.append(n.toFloat(), n * 1_000L)
            assertEquals(
                n,
                s.values.size * s.samplesPerBucket + s.pendingCount,
                "invariant broke after $n appends: $s",
            )
        }
    }

    @Test
    fun `samplesPerBucket doubles each time the series fills up`() {
        val values = (1..16).map { it.toFloat() }
        var s = series(capacity = 4)

        s = s.appendAll(values.take(3))
        assertEquals(1, s.samplesPerBucket)

        s = s.append(values[3], 3_000L) // 4th commit reaches capacity
        assertEquals(2, s.samplesPerBucket)

        s = s.appendAll(values.drop(4).take(4), stepMillis = 1_000L)
        assertEquals(4, s.samplesPerBucket)

        s = s.appendAll(values.drop(8), stepMillis = 1_000L)
        assertEquals(8, s.samplesPerBucket)
    }

    @Test
    fun `a merge leaves nothing pending and exactly half the capacity committed`() {
        val s = series(capacity = 4).appendAll((1..4).map { it.toFloat() })

        assertEquals(2, s.values.size)
        assertEquals(0, s.pendingCount)
        assertNull(s.pendingPeak)
    }

    @Test
    fun `peak survives many merges`() {
        // One deliberate spike early on, so it has to survive being merged repeatedly.
        val values = MutableList(200) { 5f }
        values[3] = 137f
        val s = series(capacity = 4).appendAll(values)

        assertEquals(137f, s.peak)
    }

    @Test
    fun `a discharge spike survives a merge against a smaller charging value`() {
        // Buckets merge by magnitude with the sign kept: plain max would report +2 A here and the
        // discharge would vanish from both the trend and the peak.
        val s = series(capacity = 4).appendAll(listOf(-18f, 2f, 1f, 1f))

        assertEquals(-18f, s.peak)
        assertTrue(s.points.any { it == -18f }, "points were ${s.points}")
    }

    @Test
    fun `points include the bucket still filling so the tip moves on every reading`() {
        val s = series(capacity = 4).appendAll(listOf(1f, 2f, 3f, 4f)) // samplesPerBucket is now 2
        val before = s.points

        val after = s.append(99f, 9_000L)

        assertEquals(1, after.pendingCount)
        assertEquals(before.size + 1, after.points.size)
        assertEquals(99f, after.points.last())
    }

    @Test
    fun `span is the distance between the oldest and the newest sample`() {
        val s = series().append(1f, 10_000L).append(2f, 70_000L)

        assertEquals(60_000L, s.spanMillis)
    }

    @Test
    fun `a single sample spans nothing and an empty series has no peak`() {
        assertEquals(0L, series().spanMillis)
        assertNull(series().peak)
        assertEquals(0L, series().append(4f, 1_000L).spanMillis)
    }

    @Test
    fun `an odd or too small capacity is rejected`() {
        assertThrows<IllegalArgumentException> { MetricSeries(capacity = 5) }
        assertThrows<IllegalArgumentException> { MetricSeries(capacity = 1) }
    }

    @Test
    fun `the of factory replays samples in order`() {
        val s = MetricSeries.of(listOf(1f, 5f, 3f), startMillis = 1_000L, stepMillis = 500L)

        assertEquals(5f, s.peak)
        assertEquals(1_000L, s.firstSampleMillis)
        assertEquals(2_000L, s.lastSampleMillis)
        assertEquals(1_000L, s.spanMillis)
    }

    @Test
    fun `the peak of the buckets is the peak of every reading ever appended`() {
        // Deterministic pseudo-random walk, so the assertion is exact rather than approximate.
        var x = 12345L
        val values = List(500) {
            x = (x * 1103515245 + 12345) and 0x7FFFFFFF
            (x % 1000).toFloat() - 400f
        }
        val expected = values.maxByOrNull { abs(it) }

        assertEquals(expected, series(capacity = 8).appendAll(values).peak)
    }
}
