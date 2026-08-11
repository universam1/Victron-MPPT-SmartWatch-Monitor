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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
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
    val currentColor = Color(VictronPalette.currentColor(values?.batteryCurrent))

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

        PowerArc(
            fraction = sampleSnapshot.batteryCurrentFraction(15.0),
            color = currentColor,
            trackColor = Color(VictronPalette.TRACK),
            modifier = Modifier.fillMaxSize().padding(3.dp),
            strokeWidth = 11.dp,
            startAngle = 38f,
            sweepAngle = 104f,
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

            // PV Watts — large
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = values?.pvPowerW?.toString() ?: Formatting.PLACEHOLDER,
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 48.sp),
                    color = solar,
                )
                Text(
                    text = " W",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(VictronPalette.TEXT_DIM),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // Battery Amps — slightly smaller
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = values?.batteryCurrent?.let {
                        String.format(java.util.Locale.US, "%.1f", it)
                    } ?: Formatting.PLACEHOLDER,
                    style = MaterialTheme.typography.displayMedium,
                    color = currentColor,
                )
                Text(
                    text = " A",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(VictronPalette.TEXT_DIM),
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }

            // Age
            Text(
                text = Formatting.age(sampleSnapshot, System.currentTimeMillis()),
                style = MaterialTheme.typography.labelSmall,
                color = Color(VictronPalette.TEXT_DIM),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
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
            PreviewDetailButton(Icons.Filled.BatteryChargingFull, "Battery", Formatting.volts(values?.batteryVoltage), Color(VictronPalette.BATTERY))
            PreviewDetailButton(Icons.Filled.WbSunny, "Solar", Formatting.watts(values?.batteryPowerW), Color(VictronPalette.SOLAR))
            PreviewDetailButton(Icons.Filled.PowerSettingsNew, "State", values?.chargerStateLabel ?: "–", Color(VictronPalette.TEXT_DIM))
            PreviewDetailButton(Icons.Filled.WbSunny, "Yield today", Formatting.energy(values?.yieldTodayWh), Color(VictronPalette.YIELD))
        }
    }
}

@Composable
private fun PreviewDetailButton(icon: ImageVector, label: String, value: String, valueColor: Color) {
    Button(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(VictronPalette.SURFACE),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color(VictronPalette.TEXT_DIM),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(VictronPalette.TEXT_DIM),
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = valueColor,
            )
        }
    }
}
