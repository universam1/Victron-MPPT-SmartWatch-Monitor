package de.universam.victron.data.model

import de.universam.victron.data.model.SolarChargerValues

/**
 * In-memory ring buffer of recent readings for sparkline graphs. Not persisted.
 *
 * Capped at [MAX_ENTRIES] per metric. One entry per *reading*, not per radio reception — the
 * repository filters repeats of the same advertisement before appending — so at a Victron's ~1 Hz
 * advertising rate 60 entries is roughly the last minute.
 *
 * Immutable: [append] returns a new instance so that Compose observes the change when the
 * containing StateFlow is updated (reference equality on `List` is what triggers recomposition).
 */
public data class ReadingHistory(
    public val pvPowerW: List<Float> = emptyList(),
    public val batteryCurrent: List<Float> = emptyList(),
    public val batteryVoltage: List<Float> = emptyList(),
    public val yieldTodayWh: List<Float> = emptyList(),
    public val loadCurrent: List<Float> = emptyList(),
    private val maxEntries: Int = MAX_ENTRIES,
) {
    public fun append(values: SolarChargerValues): ReadingHistory = copy(
        pvPowerW = values.pvPowerW?.let { pushed(pvPowerW, it.toFloat()) } ?: pvPowerW,
        batteryCurrent = values.batteryCurrent?.let { pushed(batteryCurrent, it.toFloat()) } ?: batteryCurrent,
        batteryVoltage = values.batteryVoltage?.let { pushed(batteryVoltage, it.toFloat()) } ?: batteryVoltage,
        yieldTodayWh = values.yieldTodayWh?.let { pushed(yieldTodayWh, it.toFloat()) } ?: yieldTodayWh,
        loadCurrent = values.loadCurrent?.let { pushed(loadCurrent, it.toFloat()) } ?: loadCurrent,
    )

    private fun pushed(list: List<Float>, value: Float): List<Float> {
        val result = if (list.size >= maxEntries) list.drop(1) + value else list + value
        return result
    }

    public companion object {
        private const val MAX_ENTRIES = 60
    }
}
