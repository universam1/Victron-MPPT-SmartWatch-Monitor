package de.universam.victron.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.android.tools.screenshot.PreviewTest
import de.universam.victron.data.Formatting
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.SnapshotStatus
import de.universam.victron.data.model.SolarChargerValues

private val BACKGROUND = Color(VictronPalette.BACKGROUND)

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
    bleName = "MobiBlue",
    label = null,
    modelId = 0xA060,
    modelName = "SmartSolar MPPT 100/20-48V",
    recordTypeCode = 1,
    recordLabel = "Solar Charger",
    rssi = -65,
    receivedAtEpochMillis = System.currentTimeMillis() - 12_000,
    status = SnapshotStatus.DECODED,
    solarCharger = sampleCharger,
    observedPvPeakW = 320,
)

@PreviewTest
@Preview(widthDp = 240, heightDp = 240, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewPowerArc() {
    val solar = Color(VictronPalette.SOLAR)
    Box(
        modifier = Modifier.size(240.dp).background(BACKGROUND).padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        PowerArc(
            fraction = 0.4f,
            color = solar,
            trackColor = Color(VictronPalette.TRACK),
            modifier = Modifier.fillMaxSize(),
            gradientColors = listOf(
                Color(VictronPalette.HEAT_LOW),
                Color(VictronPalette.HEAT_MID),
                Color(VictronPalette.HEAT_HIGH),
            ),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "142",
                    style = MaterialTheme.typography.displayMedium,
                    color = solar,
                )
                Text(
                    text = " W",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(VictronPalette.TEXT_DIM),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                text = "Bulk",
                style = MaterialTheme.typography.labelSmall,
                color = Color(VictronPalette.TEXT_DIM),
            )
        }
    }
}

@PreviewTest
@Preview(widthDp = 240, heightDp = 240, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewWatchFace() {
    Box(modifier = Modifier.size(240.dp)) {
        GaugeFace(
            snapshot = sampleSnapshot,
            peakWatts = 380,
            batteryCurrentMax = 15.0,
            now = System.currentTimeMillis(),
        )
    }
}

/** The whole hero surface, so a gauge that collapses inside the lazy list shows up here. */
@PreviewTest
@Preview(widthDp = 240, heightDp = 240, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewHero() {
    Box(modifier = Modifier.size(240.dp)) {
        HeroContent(
            snapshot = sampleSnapshot,
            peakWatts = 380,
            batteryCurrentMax = 15.0,
            now = System.currentTimeMillis(),
            deviceCount = 1,
        )
    }
}

/** No device found yet: same layout, placeholders instead of values, navigation still there. */
@PreviewTest
@Preview(widthDp = 240, heightDp = 240, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewHeroEmpty() {
    Box(modifier = Modifier.size(240.dp)) {
        HeroContent(
            snapshot = null,
            peakWatts = 0,
            batteryCurrentMax = 15.0,
            now = System.currentTimeMillis(),
            status = "No Victron device yet",
        )
    }
}

@PreviewTest
@Preview(widthDp = 240, heightDp = 240, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewDetailList() {
    val values = sampleSnapshot.solarCharger

    Box(
        modifier = Modifier.size(240.dp).background(BACKGROUND).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            // Uses the real composables from HeroScreen (internal visibility)
            BatteryDetailButton(
                voltage = Formatting.volts(values?.batteryVoltage),
                current = Formatting.amps(values?.batteryCurrent),
                power = Formatting.watts(values?.batteryPowerW),
            )
            DetailButton(
                icon = Icons.Filled.PowerSettingsNew,
                label = "State",
                value = values?.chargerStateLabel ?: "–",
                valueColor = Color(VictronPalette.TEXT_DIM),
            )
            DetailButton(
                icon = Icons.Filled.WbSunny,
                label = "Yield today",
                value = Formatting.energy(values?.yieldTodayWh),
                valueColor = Color(VictronPalette.YIELD),
            )
        }
    }
}
