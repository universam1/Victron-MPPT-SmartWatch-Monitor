package de.universam.victron.mobile.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.universam.victron.data.Formatting
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.model.MetricSeries
import de.universam.victron.mobile.R

/** Full scale when there is no device to read a rating or a peak from. */
private const val FALLBACK_MAX_AMPS = 30.0

private val TEXT_DIM = Color(VictronPalette.TEXT_DIM)

/**
 * Battery current section: label, value, and trend sparkline. The arc itself is now drawn inside
 * [PvArcGauge] on the same circle as the PV arc (matching the watch layout), so this composable
 * only provides the textual context beneath it.
 *
 * [sparklineHeight] trades trend detail for vertical space — a landscape phone has much less of
 * the latter than a portrait one.
 */
@Composable
fun CurrentArcGauge(
    amps: Double?,
    maxAmps: Double?,
    stale: Boolean,
    modifier: Modifier = Modifier,
    series: MetricSeries? = null,
    sparklineHeight: Dp = 72.dp,
) {
    val scale = maxAmps ?: FALLBACK_MAX_AMPS
    val arcColor = if (stale) TEXT_DIM else Color(VictronPalette.currentColor(amps))

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.BatteryChargingFull,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).padding(end = 4.dp),
                    tint = TEXT_DIM,
                )
                Text(
                    text = stringResource(R.string.label_battery_current),
                    fontSize = 14.sp,
                    color = TEXT_DIM,
                )
                TrendSpanLabel(series = series, modifier = Modifier.padding(start = 8.dp), fontSize = 11.sp)
            }
            Text(
                text = Formatting.amps(amps),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = arcColor,
            )
        }

        // Trend sparkline.
        val points = series?.points.orEmpty()
        if (points.size >= 2) {
            Sparkline(
                values = points,
                color = arcColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sparklineHeight),
            )
        }

        // Scale label — shows what the full arc represents.
        Text(
            text = stringResource(R.string.gauge_of_scale_amps, Formatting.amps(scale)),
            fontSize = 11.sp,
            color = TEXT_DIM,
            maxLines = 1,
        )
    }
}
