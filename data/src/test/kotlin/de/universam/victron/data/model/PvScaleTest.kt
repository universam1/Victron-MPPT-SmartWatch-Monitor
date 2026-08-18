package de.universam.victron.data.model

import de.universam.victron.data.Formatting
import de.universam.victron.protocol.VictronModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Scaling of the power and current gauges, and the formatting rules the gauges depend on. */
class PvScaleTest {

    /** A model id that is not in the table, so nothing can be derived from its name. */
    private val UNKNOWN_MODEL = 0xFFFF

    /** [modelId] `0xA053` is a SmartSolar MPPT 75/15 — 15 A. `UNKNOWN_MODEL` has no rating. */
    private fun snapshot(
        pvPowerW: Int?,
        observedPeak: Int = 0,
        ageMillis: Long = 0,
        modelId: Int = 0xA053,
        batteryCurrent: Double = 2.0,
        observedCurrentPeak: Double = 0.0,
    ) = DeviceSnapshot(
        address = "AA:BB",
        bleName = null,
        label = null,
        modelId = modelId,
        modelName = VictronModels.nameFor(modelId),
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
            batteryCurrent = batteryCurrent,
            pvPowerW = pvPowerW,
            yieldTodayWh = 420,
            loadCurrent = null,
        ),
        observedPvPeakW = observedPeak,
        observedCurrentPeakA = observedCurrentPeak,
    )

    @Test
    fun `the model rating times the battery voltage is the power scale`() {
        // 15 A charger at 13.4 V can put 201 W into the battery, whatever the array can make.
        assertEquals(201, snapshot(pvPowerW = 12).pvScaleMaxW())
        assertEquals(0.06f, snapshot(pvPowerW = 12).pvFraction(), 1e-3f)
    }

    @Test
    fun `the model rating wins over a higher observed peak`() {
        assertEquals(201, snapshot(pvPowerW = 40, observedPeak = 900).pvScaleMaxW())
    }

    @Test
    fun `an unknown model falls back to the highest power seen`() {
        // 190 W seen -> next 100 W step
        assertEquals(200, snapshot(pvPowerW = 40, observedPeak = 190, modelId = UNKNOWN_MODEL).pvScaleMaxW())
        // 60 W seen -> 50 W steps below 100 W
        assertEquals(100, snapshot(pvPowerW = 60, modelId = UNKNOWN_MODEL).pvScaleMaxW())
        // 1.4 kW array -> 250 W steps
        assertEquals(1500, snapshot(pvPowerW = 1400, modelId = UNKNOWN_MODEL).pvScaleMaxW())
    }

    @Test
    fun `a dark morning does not look like a full array`() {
        val dark = snapshot(pvPowerW = 3, modelId = UNKNOWN_MODEL)

        assertEquals(50, dark.pvScaleMaxW(), "50 W floor")
        assertEquals(0.06f, dark.pvFraction(), 1e-4f)
    }

    @Test
    fun `the observed peaks survive advertisements that report less`() {
        val previous = snapshot(
            pvPowerW = 260,
            observedPeak = 260,
            batteryCurrent = 19.0,
            observedCurrentPeak = 19.0,
            modelId = UNKNOWN_MODEL,
        )

        val carried = snapshot(pvPowerW = 12, batteryCurrent = 1.0, modelId = UNKNOWN_MODEL).carryOver(previous)

        assertEquals(260, carried.observedPvPeakW)
        assertEquals(300, carried.pvScaleMaxW())
        assertEquals(19.0, carried.observedCurrentPeakA, 1e-9)
        assertEquals(20.0, carried.batteryCurrentMaxA(), 1e-9)
    }

    @Test
    fun `a missing power reading leaves the arc empty instead of guessing`() {
        assertEquals(0f, snapshot(pvPowerW = null).pvFraction())
    }

    @Test
    fun `the arc never overshoots when the array beats its rating`() {
        assertEquals(1f, snapshot(pvPowerW = 500).pvFraction())
    }

    @Test
    fun `the current arc is scaled by the charger rating`() {
        assertEquals(15.0, snapshot(pvPowerW = 100).batteryCurrentMaxA(), 1e-9)
        assertEquals(0.5f, snapshot(pvPowerW = 100, batteryCurrent = 7.5).batteryCurrentFraction(), 1e-4f)
        // Discharge is shown by magnitude, not by an empty arc.
        assertEquals(0.2f, snapshot(pvPowerW = 100, batteryCurrent = -3.0).batteryCurrentFraction(), 1e-4f)
    }

    @Test
    fun `an unknown model scales the current arc by the highest current seen`() {
        val unknown = snapshot(pvPowerW = 100, batteryCurrent = 2.0, observedCurrentPeak = 11.0, modelId = UNKNOWN_MODEL)

        assertEquals(15.0, unknown.batteryCurrentMaxA(), 1e-9, "11 A seen -> next 5 A step")
        // A trickle keeps the 5 A floor, so it never divides by zero and never looks like a full charge.
        val trickle = snapshot(pvPowerW = 1, batteryCurrent = 0.1, modelId = UNKNOWN_MODEL)
        assertEquals(5.0, trickle.batteryCurrentMaxA(), 1e-9)
        assertEquals(0.02f, trickle.batteryCurrentFraction(), 1e-4f)
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
