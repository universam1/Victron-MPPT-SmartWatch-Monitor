package de.universam.victron.data

import de.universam.victron.data.model.DeviceSnapshot
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Number formatting shared by the watch app, the tile and the phone app so a value never looks
 * different depending on where you read it.
 */
public object Formatting {

    public fun volts(value: Double?): String =
        value?.let { String.format(Locale.US, "%.2f V", it) } ?: PLACEHOLDER

    public fun amps(value: Double?): String =
        value?.let { String.format(Locale.US, "%.1f A", it) } ?: PLACEHOLDER

    public fun watts(value: Int?): String = value?.let { "$it W" } ?: PLACEHOLDER

    public fun watts(value: Double?): String = value?.let { "${it.roundToInt()} W" } ?: PLACEHOLDER

    /** Energy as kWh above 1 kWh, Wh below, because a tile has no room for "0.03 kWh". */
    public fun energy(wattHours: Int?): String = when {
        wattHours == null -> PLACEHOLDER
        abs(wattHours) >= 1000 -> String.format(Locale.US, "%.2f kWh", wattHours / 1000.0)
        else -> "$wattHours Wh"
    }

    /** Compact "how old is this value" string: `now`, `42s`, `7m`, `3h`, `2d`. */
    public fun age(ageMillis: Long): String {
        val seconds = ageMillis / 1000
        return when {
            seconds < 5 -> "now"
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3600}h"
            else -> "${seconds / 86_400}d"
        }
    }

    public fun age(snapshot: DeviceSnapshot, nowEpochMillis: Long = System.currentTimeMillis()): String =
        age(snapshot.ageMillis(nowEpochMillis))

    /** Values older than this are shown as stale — a Victron device advertises every second. */
    public const val STALE_AFTER_MILLIS: Long = 90_000

    public fun isStale(snapshot: DeviceSnapshot, nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        snapshot.ageMillis(nowEpochMillis) > STALE_AFTER_MILLIS

    /** Signal strength as 0..4, for a small bar indicator. */
    public fun signalBars(rssi: Int): Int = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }

    public const val PLACEHOLDER: String = "–"
}
