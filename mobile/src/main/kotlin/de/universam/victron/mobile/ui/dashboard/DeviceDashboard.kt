package de.universam.victron.mobile.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.universam.victron.data.Formatting
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.ReadingHistory
import de.universam.victron.mobile.R

private val TEXT_DIM = Color(VictronPalette.TEXT_DIM)
private val TEXT_PRIMARY = Color(VictronPalette.TEXT)
private val BATTERY = Color(VictronPalette.BATTERY)
private val YIELD = Color(VictronPalette.YIELD)
private val CHARGING = Color(VictronPalette.CHARGING)
private val ERROR = Color(VictronPalette.ERROR)
private val TRACK = Color(0xFF1A2332)
private val SURFACE = Color(0xFF121E2E)
private val SURFACE_LIGHT = Color(0xFF1A2940)

/**
 * Fullscreen dashboard layout for a single decoded device. Shows the PV arc gauge prominently,
 * a charger state chip, battery current bar with sparkline, then value tiles with sparklines.
 */
@Composable
fun DeviceDashboard(
    snapshot: DeviceSnapshot,
    peakWatts: Int,
    now: Long,
    modifier: Modifier = Modifier,
    history: ReadingHistory? = null,
) {
    val values = snapshot.solarCharger
    val stale = Formatting.isStale(snapshot, now)
    val scaleMax = snapshot.pvScaleMaxW(peakWatts)

    val stateLabel = if (values?.hasError == true) {
        values.chargerErrorLabel ?: "Err ${values.chargerErrorCode}"
    } else {
        values?.chargerStateLabel
    }
    val stateColor = if (values?.hasError == true) ERROR else CHARGING

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Header: device name + age
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = snapshot.displayName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = TEXT_PRIMARY,
                )
                Text(
                    text = Formatting.age(snapshot, now),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (stale) ERROR else TEXT_DIM,
                )
            }
            HorizontalDivider(color = TRACK, thickness = 1.dp)
        }

        // PV Arc Gauge — dominant visual, with sparkline inside
        PvArcGauge(
            fraction = snapshot.pvFraction(peakWatts),
            watts = values?.pvPowerW,
            scaleMaxW = scaleMax,
            stale = stale,
            sparklineValues = history?.pvPowerW.orEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )


        // Battery Current bar with sparkline
        CurrentBar(
            amps = values?.batteryCurrent,
            stale = stale,
            sparklineValues = history?.batteryCurrent.orEmpty(),
        )

        // Value tiles grid — voltage, yield, charger state, (+ load if available)
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ValueTile(
                label = stringResource(R.string.label_voltage),
                value = Formatting.volts(values?.batteryVoltage),
                accentColor = BATTERY,
                stale = stale,
                modifier = Modifier.weight(1f),
                sparklineValues = history?.batteryVoltage.orEmpty(),
            )
            ValueTile(
                label = stringResource(R.string.label_yield_today),
                value = Formatting.energy(values?.yieldTodayWh),
                accentColor = YIELD,
                stale = stale,
                modifier = Modifier.weight(1f),
                sparklineValues = history?.yieldTodayWh.orEmpty(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Charger state tile — accent-washed box matching ValueTile style
            val chipColor = if (stale) TEXT_DIM else stateColor
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.verticalGradient(listOf(SURFACE_LIGHT, SURFACE)))
                    .background(chipColor.copy(alpha = 0.12f))
                    .padding(16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.label_state),
                        fontSize = 12.sp,
                        color = TEXT_DIM,
                    )
                    Text(
                        text = stateLabel ?: Formatting.PLACEHOLDER,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = chipColor,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            // Load tile — or empty spacer
            val loadCurrent = values?.loadCurrent
            if (loadCurrent != null) {
                ValueTile(
                    label = stringResource(R.string.label_load),
                    value = Formatting.amps(loadCurrent),
                    accentColor = BATTERY,
                    stale = stale,
                    modifier = Modifier.weight(1f),
                    sparklineValues = history?.loadCurrent.orEmpty(),
                )
            } else {
                Box(modifier = Modifier.weight(1f))
            }
        }
    }
}
