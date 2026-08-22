package de.universam.victron.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/** The per-device trend buffer: which metrics advance, and when the window is thrown away. */
class ReadingHistoryTest {

    private val berlin = TimeZone.getTimeZone("Europe/Berlin")

    private fun values(
        pvPowerW: Int? = 100,
        batteryCurrent: Double? = 4.0,
        batteryVoltage: Double? = 13.4,
        yieldTodayWh: Int? = 500,
        loadCurrent: Double? = 1.0,
    ) = SolarChargerValues(
        chargerStateLabel = "Bulk",
        chargerStateCode = 3,
        chargerErrorLabel = "No error",
        chargerErrorCode = 0,
        batteryVoltage = batteryVoltage,
        batteryCurrent = batteryCurrent,
        pvPowerW = pvPowerW,
        yieldTodayWh = yieldTodayWh,
        loadCurrent = loadCurrent,
    )

    /** 2025-06-10 12:00 local in Berlin (UTC+2). */
    private val noon = 1_749_549_600_000L

    private fun ReadingHistory.at(millis: Long, v: SolarChargerValues = values()) =
        append(v, millis, berlin)

    @Test
    fun `a metric the device did not report does not advance`() {
        val h = ReadingHistory().at(noon).at(noon + 1_000, values(pvPowerW = null))

        assertEquals(1, h.pvPowerW.points.size)
        assertEquals(2, h.batteryCurrent.points.size)
    }

    @Test
    fun `a new local day clears every series`() {
        val h = ReadingHistory().at(noon).at(noon + 86_400_000L)

        assertEquals(1, h.pvPowerW.points.size)
        assertEquals(1, h.batteryCurrent.points.size)
        assertEquals(1, h.batteryVoltage.points.size)
        assertEquals(1, h.loadCurrent.points.size)
    }

    @Test
    fun `a new local day also clears a metric that is absent from the triggering reading`() {
        // The bug a per-metric day check would have: `loadCurrent` is null in the first reading of
        // the new day, so a per-series check would never fire and yesterday's points would stay.
        val h = ReadingHistory()
            .at(noon, values(loadCurrent = 3.0))
            .at(noon + 86_400_000L, values(loadCurrent = null))

        assertTrue(h.loadCurrent.points.isEmpty(), "load points were ${h.loadCurrent.points}")
    }

    @Test
    fun `readings either side of local midnight are different days even within one UTC day`() {
        // 23-30 local on 2025-06-10 and 00-30 local on 2025-06-11 are both 2025-06-10 in UTC.
        val lateEvening = noon + 11 * 3_600_000L + 1_800_000L
        val afterMidnight = lateEvening + 3_600_000L

        assertNotEquals(
            localDayIndex(lateEvening, berlin),
            localDayIndex(afterMidnight, berlin),
        )
        assertEquals(1, ReadingHistory().at(lateEvening).at(afterMidnight).pvPowerW.points.size)
    }

    @Test
    fun `an evening and a late night reading on the same local day share a window`() {
        val evening = noon + 9 * 3_600_000L // 21:00 local
        val laterSameDay = evening + 3_600_000L // 22:00 local

        assertEquals(2, ReadingHistory().at(evening).at(laterSameDay).pvPowerW.points.size)
    }

    @Test
    fun `the same instant falls on different days in different zones`() {
        // The offset really is applied: 10-00 UTC is 2025-06-10 in Berlin and 2025-06-11 already
        // in Kiritimati (UTC+14).
        val kiritimati = TimeZone.getTimeZone("Pacific/Kiritimati")

        assertNotEquals(localDayIndex(noon, berlin), localDayIndex(noon, kiritimati))
    }

    @Test
    fun `a DST switch does not split a local day in two`() {
        // 2025-03-30, the day Berlin skips 02:00-03:00, so the local day is only 23 hours long.
        val dstMorning = 1_743_294_600_000L // 2025-03-30 01:30 local
        val dstEvening = dstMorning + 20 * 3_600_000L // 22:30 local, still the same day

        assertEquals(localDayIndex(dstMorning, berlin), localDayIndex(dstEvening, berlin))
    }

    @Test
    fun `a yield counter that drops mid day restarts only that series`() {
        // The charger resets its own day counter on its own clock, not our local midnight.
        val h = ReadingHistory()
            .at(noon, values(yieldTodayWh = 3200))
            .at(noon + 1_000, values(yieldTodayWh = 0))

        assertEquals(1, h.yieldTodayWh.points.size)
        assertEquals(0f, h.yieldTodayWh.peak)
        assertEquals(2, h.pvPowerW.points.size)
    }

    @Test
    fun `a rising yield counter keeps accumulating`() {
        val h = ReadingHistory()
            .at(noon, values(yieldTodayWh = 100))
            .at(noon + 1_000, values(yieldTodayWh = 220))

        assertEquals(2, h.yieldTodayWh.points.size)
        assertEquals(220f, h.yieldTodayWh.peak)
    }

    @Test
    fun `the span grows with the runtime instead of sliding`() {
        var h = ReadingHistory()
        repeat(500) { i -> h = h.at(noon + i * 1_000L) }

        assertEquals(499_000L, h.pvPowerW.spanMillis)
        assertTrue(h.pvPowerW.points.size <= MetricSeries.CAPACITY)
    }
}
