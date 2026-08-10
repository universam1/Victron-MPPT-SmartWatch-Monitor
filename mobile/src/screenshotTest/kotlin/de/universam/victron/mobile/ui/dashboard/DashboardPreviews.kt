package de.universam.victron.mobile.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.ReadingHistory
import de.universam.victron.data.model.SnapshotStatus
import de.universam.victron.data.model.SolarChargerValues

private val BG_BRUSH = Brush.verticalGradient(listOf(Color(0xFF0A1628), Color(0xFF0D1F3C)))

// Sample sparkline data — a gentle wave pattern
private val sampleSparkline = (0 until 30).map { i ->
    (kotlin.math.sin(i * 0.4) * 2.0 + 4.2).toFloat()
}
private val sampleVoltageSparkline = (0 until 30).map { i ->
    (kotlin.math.sin(i * 0.3) * 0.3 + 13.4).toFloat()
}
private val samplePowerSparkline = (0 until 30).map { i ->
    (kotlin.math.sin(i * 0.5) * 40 + 140).toFloat()
}
private val sampleYieldSparkline = (0 until 30).map { i ->
    (820 + i * 1.5f)
}

private val sampleCharger = SolarChargerValues(
    chargerStateLabel = "Bulk",
    chargerStateCode = 3,
    chargerErrorLabel = null,
    chargerErrorCode = 0,
    batteryVoltage = 13.42,
    batteryCurrent = 4.2,
    pvPowerW = 142,
    yieldTodayWh = 847,
    loadCurrent = 1.2,
)

private val sampleSnapshot = DeviceSnapshot(
    address = "AA:BB:CC:DD:EE:FF",
    bleName = "SmartSolar 100|30",
    label = "Roof Array",
    modelId = 0xA060,
    modelName = "SmartSolar 100|30",
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
    val history = ReadingHistory().apply {
        sampleSparkline.forEach { batteryCurrent.add(it) }
        sampleVoltageSparkline.forEach { batteryVoltage.add(it) }
        samplePowerSparkline.forEach { pvPowerW.add(it) }
        sampleYieldSparkline.forEach { yieldTodayWh.add(it) }
        sampleSparkline.forEach { loadCurrent.add(it * 0.3f) }
    }
    Box(modifier = Modifier.fillMaxSize().background(BG_BRUSH)) {
        DeviceDashboard(
            snapshot = sampleSnapshot,
            peakWatts = 380,
            now = System.currentTimeMillis(),
            history = history,
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
            peakWatts = 380,
            now = System.currentTimeMillis(),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 300, heightDp = 300)
@Composable
fun PreviewPvArcGauge() {
    Box(modifier = Modifier.size(300.dp).background(BG_BRUSH).padding(16.dp)) {
        PvArcGauge(
            fraction = 0.37f,
            watts = 142,
            scaleMaxW = 380,
            stale = false,
        )
    }
}

@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 350, heightDp = 80)
@Composable
fun PreviewCurrentBarCharging() {
    Box(modifier = Modifier.fillMaxWidth().height(80.dp).background(BG_BRUSH).padding(16.dp)) {
        CurrentBar(amps = 4.2, stale = false)
    }
}

@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 350, heightDp = 80)
@Composable
fun PreviewCurrentBarDischarging() {
    Box(modifier = Modifier.fillMaxWidth().height(80.dp).background(BG_BRUSH).padding(16.dp)) {
        CurrentBar(amps = -2.8, stale = false)
    }
}
