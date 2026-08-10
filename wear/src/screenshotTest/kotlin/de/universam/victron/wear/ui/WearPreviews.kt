package de.universam.victron.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val values = sampleSnapshot.solarCharger
    val solar = Color(VictronPalette.SOLAR)
    val now = System.currentTimeMillis()

    Box(
        modifier = Modifier.size(240.dp).background(BACKGROUND),
        contentAlignment = Alignment.Center,
    ) {
        PowerArc(
            fraction = sampleSnapshot.pvFraction(380),
            color = solar,
            trackColor = Color(VictronPalette.TRACK),
            modifier = Modifier.fillMaxSize().padding(3.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = sampleSnapshot.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = Color(VictronPalette.TEXT_DIM),
            )

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = values?.pvPowerW?.toString() ?: Formatting.PLACEHOLDER,
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
                text = values?.chargerStateLabel ?: Formatting.PLACEHOLDER,
                style = MaterialTheme.typography.labelSmall,
                color = Color(VictronPalette.TEXT_DIM),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(VictronPalette.SURFACE))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = Formatting.volts(values?.batteryVoltage),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(VictronPalette.BATTERY),
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(VictronPalette.SURFACE))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = Formatting.amps(values?.batteryCurrent),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(VictronPalette.currentColor(values?.batteryCurrent)),
                    )
                }
            }

            Text(
                text = "${Formatting.energy(values?.yieldTodayWh)} · ${Formatting.age(sampleSnapshot, now)}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(VictronPalette.YIELD),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
