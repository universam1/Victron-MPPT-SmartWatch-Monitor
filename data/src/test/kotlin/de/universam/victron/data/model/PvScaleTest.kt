package de.universam.victron.data.model

import de.universam.victron.data.Formatting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Scaling of the power gauge, and the little formatting rules the gauge depends on. */
class PvScaleTest {

    private fun snapshot(pvPowerW: Int?, observedPeak: Int = 0, ageMillis: Long = 0) = DeviceSnapshot(
        address = "AA:BB",
        bleName = null,
        label = null,
        modelId = 0xA053,
        modelName = "SmartSolar MPPT 75/15",
        recordTypeCode = 1,
        recordLabel = "Solar charger",
        rssi = -60,
        receivedAtEpochMillis = 1_000_000 - ageMillis,
        status = SnapshotStatus.DECODED,
        solarCharger = SolarChargerValues(
            chargerStateLabel = "Bulk",
            chargerStateCode = 3,
            chargerErrorLabel = "No error",
            chargerErrorCode = 0,
            batteryVoltage = 13.4,
            batteryCurrent = 2.0,
            pvPowerW = pvPowerW,
            yieldTodayWh = 420,
            loadCurrent = null,
        ),
        observedPvPeakW = observedPeak,
    )

    @Test
    fun `a configured array size is used as is`() {
        assertEquals(400, snapshot(pvPowerW = 12).pvScaleMaxW(configuredPeakWatts = 400))
        assertEquals(0.03f, snapshot(pvPowerW = 12).pvFraction(400), 1e-4f)
    }

    @Test
    fun `without configuration the scale follows the highest power seen`() {
        // 190 W seen -> next 100 W step
        assertEquals(200, snapshot(pvPowerW = 40, observedPeak = 190).pvScaleMaxW(0))
        // 60 W seen -> 50 W steps below 100 W
        assertEquals(100, snapshot(pvPowerW = 60).pvScaleMaxW(0))
        // 1.4 kW array -> 250 W steps
        assertEquals(1500, snapshot(pvPowerW = 1400).pvScaleMaxW(0))
    }

    @Test
    fun `a dark morning does not look like a full array`() {
        val scale = snapshot(pvPowerW = 3).pvScaleMaxW(0)

        assertEquals(50, scale, "50 W floor")
        assertEquals(0.06f, snapshot(pvPowerW = 3).pvFraction(0), 1e-4f)
    }

    @Test
    fun `the observed peak survives advertisements that report less`() {
        val previous = snapshot(pvPowerW = 260, observedPeak = 260)

        val carried = snapshot(pvPowerW = 12).carryOver(previous)

        assertEquals(260, carried.observedPvPeakW)
        assertEquals(300, carried.pvScaleMaxW(0))
    }

    @Test
    fun `a missing power reading leaves the arc empty instead of guessing`() {
        assertEquals(0f, snapshot(pvPowerW = null).pvFraction(400))
    }

    @Test
    fun `the arc never overshoots when the array beats its rating`() {
        assertEquals(1f, snapshot(pvPowerW = 500).pvFraction(400))
    }

    @Test
    fun `age and staleness read the way a human would say them`() {
        assertEquals("now", Formatting.age(0))
        assertEquals("42s", Formatting.age(42_000))
        assertEquals("7m", Formatting.age(7 * 60_000L))
        assertEquals("3h", Formatting.age(3 * 3_600_000L))
        assertEquals("420 Wh", Formatting.energy(420))
        assertEquals("1.42 kWh", Formatting.energy(1420))
        assertEquals(Formatting.PLACEHOLDER, Formatting.volts(null))
    }
}
