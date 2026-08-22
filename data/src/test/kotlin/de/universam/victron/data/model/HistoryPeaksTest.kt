package de.universam.victron.data.model

import de.universam.victron.protocol.VictronModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Where the peak tick sits on each arc. The point of these is that the tick and the fill are always
 * scaled by the same denominator, so the marker can never contradict the arc it sits on.
 */
class HistoryPeaksTest {

    /** `0xA053` is a SmartSolar MPPT 75/15, so the rating is 15 A. */
    private fun snapshot(
        pvPowerW: Int? = 100,
        batteryCurrent: Double? = 5.0,
        batteryVoltage: Double? = 13.4,
        observedPvPeakW: Int = 0,
    ) = DeviceSnapshot(
        address = "AA:BB",
        bleName = null,
        label = null,
        modelId = 0xA053,
        modelName = VictronModels.nameFor(0xA053),
        recordTypeCode = 1,
        recordLabel = "Solar charger",
        rssi = -60,
        receivedAtEpochMillis = 1_000_000,
        status = SnapshotStatus.DECODED,
        solarCharger = SolarChargerValues(
            chargerStateLabel = "Bulk",
            chargerStateCode = 3,
            chargerErrorLabel = "No error",
            chargerErrorCode = 0,
            batteryVoltage = batteryVoltage,
            batteryCurrent = batteryCurrent,
            pvPowerW = pvPowerW,
            yieldTodayWh = 500,
            loadCurrent = null,
        ),
        observedPvPeakW = observedPvPeakW,
    )

    private fun history(pv: List<Float> = emptyList(), current: List<Float> = emptyList()) =
        ReadingHistory(
            pvPowerW = MetricSeries.of(pv),
            batteryCurrent = MetricSeries.of(current),
        )

    @Test
    fun `no history means no marker`() {
        assertNull(snapshot().pvPeakFraction(null))
        assertNull(snapshot().batteryCurrentPeakFraction(null))
    }

    @Test
    fun `an all zero window means no marker`() {
        val h = history(pv = listOf(0f, 0f), current = listOf(0f, 0f))

        assertNull(snapshot().pvPeakFraction(h))
        assertNull(snapshot().batteryCurrentPeakFraction(h))
    }

    @Test
    fun `the pv marker uses the same scale as the fill`() {
        // 15 A x 13.4 V = 201 W full scale.
        val s = snapshot(pvPowerW = 100)
        val h = history(pv = listOf(50f, 180f, 100f))

        assertEquals(201, s.pvScaleMaxW())
        assertEquals(180f / 201f, s.pvPeakFraction(h)!!, 0.0001f)
        // Ahead of the live value, which is what makes the tick worth drawing.
        assertEquals(100f / 201f, s.pvFraction(), 0.0001f)
    }

    @Test
    fun `a peak above full scale is clamped to the end of the arc`() {
        val h = history(pv = listOf(5000f))

        assertEquals(1f, snapshot().pvPeakFraction(h))
    }

    @Test
    fun `a discharging window marks the magnitude of the deepest discharge`() {
        // The series keeps the signed extreme so the trend still dips; the arc is a magnitude.
        val s = snapshot(batteryCurrent = -2.0)
        val h = history(current = listOf(-12f, 2f, -2f))

        assertEquals(15.0, s.batteryCurrentMaxA())
        assertEquals(12f / 15f, s.batteryCurrentPeakFraction(h)!!, 0.0001f)
    }

    @Test
    fun `a charging window marks the highest charge current`() {
        val h = history(current = listOf(4f, 11f, 5f))

        assertEquals(11f / 15f, snapshot().batteryCurrentPeakFraction(h)!!, 0.0001f)
    }

    @Test
    fun `the all time observed peak does not move the marker`() {
        // observedPvPeakW is the fallback *scale* for an unknown model, never the marker: a known
        // model derives its scale from the rating, and the tick follows today's window only.
        val h = history(pv = listOf(60f))

        assertEquals(
            snapshot(observedPvPeakW = 0).pvPeakFraction(h),
            snapshot(observedPvPeakW = 5000).pvPeakFraction(h),
        )
    }
}
