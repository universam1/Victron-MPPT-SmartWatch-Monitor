package de.universam.victron.data.model

import kotlin.math.abs

/**
 * Where the trend window's highest PV power sits on the power arc, 0..1 — the position of the peak
 * tick. `null` when there is nothing to mark.
 *
 * Scaled by the same [DeviceSnapshot.pvScaleMaxW] as [DeviceSnapshot.pvFraction] and coerced the
 * same way, so the tick and the fill can never disagree and the watch and the phone cannot drift
 * apart. That is the whole reason this arithmetic lives in `data` rather than in either UI.
 *
 * Note the deliberate asymmetry with [DeviceSnapshot.observedPvPeakW]: that one is an all-time value
 * kept only as a fallback *scale* for an unknown model, and using it here would pin the tick at last
 * week's noon forever. This one is the current window's peak, and only ever a marker.
 */
public fun DeviceSnapshot.pvPeakFraction(history: ReadingHistory?): Float? {
    val peak = history?.pvPowerW?.peak ?: return null
    if (peak <= 0f) return null
    return (peak / pvScaleMaxW()).coerceIn(0f, 1f)
}

/**
 * The same for the battery current arc. The series keeps the signed extreme so that a discharge
 * still shows as a dip in the trend; the arc is a magnitude, so this is the only place `abs()` is
 * applied — the same `abs` [DeviceSnapshot.batteryCurrentFraction] uses.
 */
public fun DeviceSnapshot.batteryCurrentPeakFraction(history: ReadingHistory?): Float? {
    val peak = history?.batteryCurrent?.peak ?: return null
    val magnitude = abs(peak.toDouble())
    if (magnitude <= 0.0) return null
    return (magnitude / batteryCurrentMaxA()).coerceIn(0.0, 1.0).toFloat()
}
