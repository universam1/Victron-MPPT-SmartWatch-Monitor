package de.universam.victron.data.model

import java.util.TimeZone

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * Days since the epoch in [zone]. Plain offset arithmetic rather than a calendar so it works in a
 * plain JVM unit test and so the zone can be injected. The offset is looked up per timestamp, so a
 * DST switch does not shift the boundary.
 */
internal fun localDayIndex(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): Int =
    Math.floorDiv(epochMillis + zone.getOffset(epochMillis), MILLIS_PER_DAY).toInt()

/**
 * In-memory trend buffer per device, one [MetricSeries] per metric.
 *
 * Each series spans the whole time the app has been running rather than a fixed sliding window —
 * see [MetricSeries] for how that stays bounded. Not persisted and deliberately not
 * `@Serializable`: the window is "since the app started, within today", so a process restart
 * legitimately starts over and none of this belongs in `SnapshotCache`.
 *
 * The window is at most the current local day. A reading from a different local day resets *every*
 * series — including the ones that reading does not carry. Doing the day check per metric instead
 * would leave yesterday's points in any curve whose field happens to be absent from the first
 * reading after midnight (a charger without a load output, or a "not available" sentinel), and blend
 * them into today's curve. That is why the day lives here and not in [MetricSeries].
 *
 * Immutable: [append] returns a new instance so the containing StateFlow emits a new value.
 */
public data class ReadingHistory(
    public val pvPowerW: MetricSeries = MetricSeries(),
    public val batteryCurrent: MetricSeries = MetricSeries(),
    public val batteryVoltage: MetricSeries = MetricSeries(),
    public val yieldTodayWh: MetricSeries = MetricSeries(),
    public val loadCurrent: MetricSeries = MetricSeries(),
    /** Local day the samples belong to; `null` while empty. */
    public val dayIndex: Int? = null,
) {
    /**
     * @param atMillis when the reading was received — the snapshot's own timestamp, so the day is
     *   the reading's day and the whole thing stays testable without a clock.
     * @param zone injectable for tests; production always wants the current default.
     */
    public fun append(
        values: SolarChargerValues,
        atMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): ReadingHistory {
        val day = localDayIndex(atMillis, zone)
        // `!=` rather than `>`: a clock correction or a flight across the date line invalidates the
        // window just as much as midnight does.
        val rollover = dayIndex != null && dayIndex != day
        fun MetricSeries.next(value: Float?): MetricSeries =
            (if (rollover) cleared() else this).appended(value, atMillis)

        return ReadingHistory(
            pvPowerW = pvPowerW.next(values.pvPowerW?.toFloat()),
            batteryCurrent = batteryCurrent.next(values.batteryCurrent?.toFloat()),
            batteryVoltage = batteryVoltage.next(values.batteryVoltage?.toFloat()),
            yieldTodayWh = (if (rollover) yieldTodayWh.cleared() else yieldTodayWh)
                .appendedFromDayCounter(values.yieldTodayWh?.toFloat(), atMillis),
            loadCurrent = loadCurrent.next(values.loadCurrent?.toFloat()),
            dayIndex = day,
        )
    }
}

/**
 * [MetricSeries.appended] for a counter the charger resets on its own clock, which is not our local
 * midnight. A yield that went *down* is that rollover, and it is a better "new day" signal for this
 * metric than our calendar is, so today's curve starts there. Without it a single `0` after 3.2 kWh
 * would pin the rest of the day's sparkline to the top of its box, because the sparkline scales to
 * its own min/max — and [MetricSeries.peak] would keep reporting yesterday's total.
 *
 * Comparing against the newest point is exact here: the metric only rises, so a bucket's extreme is
 * its newest sample.
 */
private fun MetricSeries.appendedFromDayCounter(value: Float?, atMillis: Long): MetricSeries = when {
    value == null -> this
    points.lastOrNull()?.let { value < it } == true -> cleared().append(value, atMillis)
    else -> append(value, atMillis)
}
