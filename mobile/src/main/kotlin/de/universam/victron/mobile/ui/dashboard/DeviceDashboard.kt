package de.universam.victron.mobile.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.universam.victron.data.Formatting
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.ReadingHistory
import de.universam.victron.data.model.SolarChargerValues
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

/** Full scale of the gauge when there is no device and no configured array size yet. */
private const val FALLBACK_SCALE_W = 50

/** Share of the width the gauge gets in the two-column (landscape) arrangement. */
private const val GAUGE_COLUMN_WEIGHT = 0.50f

/**
 * Fullscreen dashboard layout for a single decoded device. Shows the PV arc gauge prominently,
 * a charger state chip, battery current bar with sparkline, then value tiles with sparklines.
 *
 * The arrangement follows the shape of the window rather than the orientation sensor: taller than
 * wide gets one scrolling column, wider than tall gets two columns with the gauge — sized to the
 * screen *height* — beside the readings, so a landscape phone is not a magnified portrait that has
 * to be scrolled.
 *
 * A `null` [snapshot] means no device has been decoded yet: the whole layout still renders, with
 * `–` for every value and the gauge at zero, so the screen is usable without a Victron in range.
 */
@Composable
fun DeviceDashboard(
    snapshot: DeviceSnapshot?,
    peakWatts: Int,
    now: Long,
    modifier: Modifier = Modifier,
    history: ReadingHistory? = null,
    onOpenSetup: (() -> Unit)? = null,
) {
    val values = snapshot?.solarCharger
    val stale = snapshot != null && Formatting.isStale(snapshot, now)
    val scaleMax = snapshot?.pvScaleMaxW(peakWatts) ?: peakWatts.coerceAtLeast(FALLBACK_SCALE_W)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val twoColumn = maxWidth > maxHeight
        val compact = twoColumn && maxHeight < 500.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (twoColumn) 10.dp else 20.dp),
        ) {
            // Header spans both columns: the device name and the way out stay in the same place
            // whichever arrangement is active.
            DashboardHeader(
                snapshot = snapshot,
                now = now,
                stale = stale,
                onOpenSetup = onOpenSetup,
            )

            if (twoColumn) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // The gauge is square and follows the height here — in landscape the width is
                    // the abundant dimension, and matching it would push everything else off screen.
                    Box(
                        modifier = Modifier.weight(GAUGE_COLUMN_WEIGHT).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        PvArcGauge(
                            fraction = snapshot?.pvFraction(peakWatts) ?: 0f,
                            watts = values?.pvPowerW,
                            scaleMaxW = scaleMax,
                            stale = stale,
                            sparklineValues = history?.pvPowerW.orEmpty(),
                            matchHeightFirst = true,
                            modifier = Modifier.fillMaxHeight(),
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f - GAUGE_COLUMN_WEIGHT)
                            .fillMaxHeight()
                            .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                        verticalArrangement = if (compact) Arrangement.spacedBy(10.dp) else Arrangement.SpaceEvenly,
                    ) {
                        CurrentBar(
                            amps = values?.batteryCurrent,
                            stale = stale,
                            sparklineValues = history?.batteryCurrent.orEmpty(),
                            // Vertical space is scarce on a phone landscape;
                            // head units (>=500dp tall) get a moderate sparkline.
                            sparklineHeight = if (compact) 36.dp else 48.dp,
                        )
                        ValueTiles(
                            values = values,
                            history = history,
                            stale = stale,
                            compact = compact,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // PV Arc Gauge — dominant visual, with sparkline inside
                    PvArcGauge(
                        fraction = snapshot?.pvFraction(peakWatts) ?: 0f,
                        watts = values?.pvPowerW,
                        scaleMaxW = scaleMax,
                        stale = stale,
                        sparklineValues = history?.pvPowerW.orEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    CurrentBar(
                        amps = values?.batteryCurrent,
                        stale = stale,
                        sparklineValues = history?.batteryCurrent.orEmpty(),
                    )

                    ValueTiles(values = values, history = history, stale = stale)
                }
            }
        }
    }
}

/** Device name, age and the button into setup. Identical in both arrangements. */
@Composable
private fun DashboardHeader(
    snapshot: DeviceSnapshot?,
    now: Long,
    stale: Boolean,
    onOpenSetup: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = snapshot?.displayName ?: stringResource(R.string.no_device),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = TEXT_PRIMARY,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = snapshot?.let { Formatting.age(it, now) } ?: Formatting.PLACEHOLDER,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (stale) ERROR else TEXT_DIM,
                )
                if (onOpenSetup != null) {
                    IconButton(onClick = onOpenSetup, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.dashboard_setup),
                            modifier = Modifier.size(20.dp),
                            tint = TEXT_DIM,
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = TRACK, thickness = 1.dp)
    }
}

/**
 * The 2×2 block of readings: voltage, yield, charger state and — when the device reports one — the
 * load current. Shared by both arrangements so the tiles cannot drift apart.
 */
@Composable
private fun ValueTiles(
    values: SolarChargerValues?,
    history: ReadingHistory?,
    stale: Boolean,
    compact: Boolean = false,
) {
    val stateLabel = if (values?.hasError == true) {
        values.chargerErrorLabel ?: "Err ${values.chargerErrorCode}"
    } else {
        values?.chargerStateLabel
    }
    val stateColor = if (values?.hasError == true) ERROR else CHARGING

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ValueTile(
            label = stringResource(R.string.label_voltage),
            value = Formatting.volts(values?.batteryVoltage),
            accentColor = BATTERY,
            stale = stale,
            icon = Icons.Filled.BatteryChargingFull,
            modifier = Modifier.weight(1f),
            sparklineValues = history?.batteryVoltage.orEmpty(),
            compact = compact,
        )
        ValueTile(
            label = stringResource(R.string.label_yield_today),
            value = Formatting.energy(values?.yieldTodayWh),
            accentColor = YIELD,
            stale = stale,
            icon = Icons.Filled.WbSunny,
            modifier = Modifier.weight(1f),
            sparklineValues = history?.yieldTodayWh.orEmpty(),
            compact = compact,
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
                .padding(if (compact) 10.dp else 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).padding(end = 4.dp),
                        tint = TEXT_DIM,
                    )
                    Text(
                        text = stringResource(R.string.label_state),
                        fontSize = 12.sp,
                        color = TEXT_DIM,
                    )
                }
                Text(
                    text = stateLabel ?: Formatting.PLACEHOLDER,
                    fontSize = if (compact) 18.sp else 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = chipColor,
                    textAlign = if (compact) TextAlign.Start else TextAlign.End,
                    modifier = if (compact) Modifier.padding(top = 2.dp) else Modifier.fillMaxWidth().padding(top = 2.dp),
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
                icon = Icons.Filled.Power,
                modifier = Modifier.weight(1f),
                sparklineValues = history?.loadCurrent.orEmpty(),
                compact = compact,
            )
        } else {
            Box(modifier = Modifier.weight(1f))
        }
    }
}
