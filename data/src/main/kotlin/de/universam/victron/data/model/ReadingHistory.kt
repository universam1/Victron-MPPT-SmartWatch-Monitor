package de.universam.victron.data.model

import de.universam.victron.data.model.SolarChargerValues

/**
 * In-memory ring buffer of recent readings for sparkline graphs. Not persisted.
 * Capped at [MAX_ENTRIES] per metric (~60 readings ≈ 1–2 minutes of live scan data).
 */
public class ReadingHistory(private val maxEntries: Int = MAX_ENTRIES) {
    public val pvPowerW: MutableList<Float> = mutableListOf()
    public val batteryCurrent: MutableList<Float> = mutableListOf()
    public val batteryVoltage: MutableList<Float> = mutableListOf()
    public val yieldTodayWh: MutableList<Float> = mutableListOf()
    public val loadCurrent: MutableList<Float> = mutableListOf()

    public fun append(values: SolarChargerValues) {
        values.pvPowerW?.let { push(pvPowerW, it.toFloat()) }
        values.batteryCurrent?.let { push(batteryCurrent, it.toFloat()) }
        values.batteryVoltage?.let { push(batteryVoltage, it.toFloat()) }
        values.yieldTodayWh?.let { push(yieldTodayWh, it.toFloat()) }
        values.loadCurrent?.let { push(loadCurrent, it.toFloat()) }
    }

    private fun push(list: MutableList<Float>, value: Float) {
        list.add(value)
        if (list.size > maxEntries) list.removeAt(0)
    }

    public companion object {
        private const val MAX_ENTRIES = 60
    }
}
