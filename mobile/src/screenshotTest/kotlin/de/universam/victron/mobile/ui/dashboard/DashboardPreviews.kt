package de.universam.victron.mobile.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.MetricSeries
import de.universam.victron.data.model.ReadingHistory
import de.universam.victron.data.model.SnapshotStatus
import de.universam.victron.data.model.SolarChargerValues

private val BG_BRUSH = Brush.verticalGradient(listOf(Color(0xFF0A1628), Color(0xFF0D1F3C)))

// Sample trend data. Power and current describe a morning that peaked and then fell back, so the
// live value sits well below the window peak and the peak tick has somewhere to be — a curve that
// merely oscillates around the live value would leave the tick sitting on the fill's own end cap
// and prove nothing.
private val sampleSparkline = (0 until 30).map { i ->
    (kotlin.math.sin(i * 0.11) * 14.0 + 12.0).toFloat() // peaks ~26 A, ends ~15 A
}
private val sampleVoltageSparkline = (0 until 30).map { i ->
    (kotlin.math.sin(i * 0.3) * 0.3 + 13.4).toFloat()
}
private val samplePowerSparkline = (0 until 30).map { i ->
    (kotlin.math.sin(i * 0.11) * 190.0 + 150.0).toFloat() // peaks ~340 W, ends ~200 W
}
private val sampleYieldSparkline = (0 until 30).map { i ->
    (820 + i * 1.5f)
}

/** Fixed step, so the span label is deterministic and the golden images stay stable. */
private const val SAMPLE_STEP_MILLIS = 150_000L

private val sampleHistory = ReadingHistory(
    batteryCurrent = MetricSeries.of(sampleSparkline, stepMillis = SAMPLE_STEP_MILLIS),
    batteryVoltage = MetricSeries.of(sampleVoltageSparkline, stepMillis = SAMPLE_STEP_MILLIS),
    pvPowerW = MetricSeries.of(samplePowerSparkline, stepMillis = SAMPLE_STEP_MILLIS),
    yieldTodayWh = MetricSeries.of(sampleYieldSparkline, stepMillis = SAMPLE_STEP_MILLIS),
    loadCurrent = MetricSeries.of(sampleSparkline.map { it * 0.3f }, stepMillis = SAMPLE_STEP_MILLIS),
)

private val sampleCharger = SolarChargerValues(
    chargerStateLabel = "Bulk",
    chargerStateCode = 3,
    chargerErrorLabel = null,
    chargerErrorCode = 0,
    batteryVoltage = 13.42,
    batteryCurrent = 15.0,
    pvPowerW = 200,
    yieldTodayWh = 847,
    loadCurrent = 1.2,
)

private val sampleSnapshot = DeviceSnapshot(
    address = "AA:BB:CC:DD:EE:FF",
    bleName = "MobiBlue",
    label = null,
    modelId = 0xA056,
    modelName = "SmartSolar MPPT 100/30",
    recordTypeCode = 1,
    recordLabel = "Solar Charger",
    rssi = -65,
    receivedAtEpochMillis = System.currentTimeMillis() - 12_000,
    status = SnapshotStatus.DECODED,
    solarCharger = sampleCharger,
    observedPvPeakW = 320,
)

private val staleSnapshot = sampleSnapshot.copy(
    receivedAtEpochMillis = System.currentTimeMillis() - 120_000,
)

@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 390, heightDp = 800)
@Composable
fun PreviewDeviceDashboard() {
    Box(modifier = Modifier.fillMaxSize().background(BG_BRUSH)) {
        DeviceDashboard(
            snapshot = sampleSnapshot,
            now = System.currentTimeMillis(),
            history = sampleHistory,
        )
    }
}

@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 390, heightDp = 800)
@Composable
fun PreviewDeviceDashboardStale() {
    Box(modifier = Modifier.fillMaxSize().background(BG_BRUSH)) {
        DeviceDashboard(
            snapshot = staleSnapshot,
            now = System.currentTimeMillis(),
            // With history, so the dimmed peak ticks and trends are covered too.
            history = sampleHistory,
        )
    }
}

/** Landscape: two columns, gauge sized to the height, everything visible without scrolling. */
@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 800, heightDp = 360)
@Composable
fun PreviewDeviceDashboardLandscape() {
    Box(modifier = Modifier.fillMaxSize().background(BG_BRUSH)) {
        DeviceDashboard(
            snapshot = sampleSnapshot,
            now = System.currentTimeMillis(),
            history = sampleHistory,
            onOpenSetup = {},
        )
    }
}

/** Landscape with nothing found yet — the arrangement must hold up on placeholders too. */
@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 800, heightDp = 360)
@Composable
fun PreviewDeviceDashboardLandscapeEmpty() {
    Box(modifier = Modifier.fillMaxSize().background(BG_BRUSH)) {
        DeviceDashboard(
            snapshot = null,
            now = System.currentTimeMillis(),
            onOpenSetup = {},
        )
    }
}

/** Head unit: large landscape, non-compact tiles with right-aligned values and full sparklines. */
@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 1280, heightDp = 720)
@Composable
fun PreviewDeviceDashboardHeadUnit() {
    Box(modifier = Modifier.fillMaxSize().background(BG_BRUSH)) {
        DeviceDashboard(
            snapshot = sampleSnapshot,
            now = System.currentTimeMillis(),
            history = sampleHistory,
            onOpenSetup = {},
        )
    }
}

/** No device decoded yet: the dashboard still renders, with placeholders instead of values. */
@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 390, heightDp = 800)
@Composable
fun PreviewDeviceDashboardEmpty() {
    Box(modifier = Modifier.fillMaxSize().background(BG_BRUSH)) {
        DeviceDashboard(
            snapshot = null,
            now = System.currentTimeMillis(),
            onOpenSetup = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 300, heightDp = 300)
@Composable
fun PreviewPvArcGauge() {
    // Centred: the gauge is wider than it is tall, so a top-aligned box would pile all the slack
    // under it and read as a layout bug rather than as the empty bottom of the arc.
    Box(
        modifier = Modifier.size(300.dp).background(BG_BRUSH).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        PvArcGauge(
            fraction = 0.79f,
            watts = 320,
            scaleMaxW = 403,
            stale = false,
            // No peak: the gauge must still render before any history exists.
            peakFraction = null,
        )
    }
}

/** The peak far ahead of the live value — the case the tick exists for. */
@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 300, heightDp = 300)
@Composable
fun PreviewPvArcGaugePeak() {
    Box(
        modifier = Modifier.size(300.dp).background(BG_BRUSH).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        PvArcGauge(
            fraction = 0.35f,
            watts = 140,
            scaleMaxW = 403,
            stale = false,
            peakFraction = 0.88f,
        )
    }
}

/** Stale: flat dim arc, and the tick dimmed rather than hidden. */
@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 300, heightDp = 300)
@Composable
fun PreviewPvArcGaugeStalePeak() {
    Box(
        modifier = Modifier.size(300.dp).background(BG_BRUSH).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        PvArcGauge(
            fraction = 0.35f,
            watts = 140,
            scaleMaxW = 403,
            stale = true,
            peakFraction = 0.88f,
        )
    }
}

@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 350, heightDp = 260)
@Composable
fun PreviewCurrentArcCharging() {
    Box(modifier = Modifier.fillMaxWidth().height(260.dp).background(BG_BRUSH).padding(16.dp)) {
        CurrentArcGauge(
            amps = 15.0,
            maxAmps = 30.0,
            stale = false,
            series = sampleHistory.batteryCurrent,
            peakFraction = 0.87f,
        )
    }
}

/** Discharging: flat orange instead of the charging gradient, and a tick close to the start. */
@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 350, heightDp = 260)
@Composable
fun PreviewCurrentArcDischarging() {
    Box(modifier = Modifier.fillMaxWidth().height(260.dp).background(BG_BRUSH).padding(16.dp)) {
        CurrentArcGauge(
            amps = -2.8,
            maxAmps = 30.0,
            stale = false,
            peakFraction = 0.14f,
        )
    }
}

/** Zero and no history: the minimal dot must still make the arc identifiable, with no tick. */
@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 350, heightDp = 260)
@Composable
fun PreviewCurrentArcZero() {
    Box(modifier = Modifier.fillMaxWidth().height(260.dp).background(BG_BRUSH).padding(16.dp)) {
        CurrentArcGauge(
            amps = 0.0,
            maxAmps = 30.0,
            stale = false,
            peakFraction = null,
        )
    }
}

/** The landscape branch in isolation: a height-bound arc gives up width and centres itself. */
@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 380, heightDp = 170)
@Composable
fun PreviewCurrentArcCompact() {
    Box(modifier = Modifier.fillMaxWidth().height(170.dp).background(BG_BRUSH).padding(16.dp)) {
        CurrentArcGauge(
            amps = 15.0,
            maxAmps = 30.0,
            stale = false,
            series = sampleHistory.batteryCurrent,
            peakFraction = 0.87f,
            sparklineHeight = 36.dp,
            maxArcHeight = 48.dp,
            strokeWidth = 10.dp,
        )
    }
}
