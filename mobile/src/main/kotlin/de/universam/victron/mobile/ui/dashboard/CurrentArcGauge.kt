package de.universam.victron.mobile.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import kotlin.math.abs

/** Full scale when there is no device to read a rating or a peak from. */
private const val FALLBACK_MAX_AMPS = 30.0

/** The watch's second arc, verbatim: the shallow bottom of the gauge ring. */
private const val START_ANGLE = 38f
private const val SWEEP_ANGLE = 104f

/**
 * The visible band of a 104° arc, as fractions of its radius. The arc spans 38°..142°, so its ends
 * sit `cos 52° = 0.6157·r` below the centre and its deepest point a full `r` below: the band is
 * `1 − cos 52°` tall and `2·sin 52°` wide. That fixes its shape at about 1:4.1 whatever the size,
 * which is exactly why it reads as the same gauge as the one on the watch bezel.
 */
private const val SAGITTA_FRACTION = 0.384339f
private const val CHORD_FRACTION = 1.576022f
private const val COS_HALF_SWEEP = 0.615661f

/** How far the glow reaches past the stroke, matching [PvArcGauge]. */
private val GLOW_EXTRA = 8.dp

/** Below this the band has no room for anything but the stroke itself. */
private val LABEL_MIN_BAND_HEIGHT = 56.dp

private val TRACK = Color(0xFF1A2332)
private val TEXT_DIM = Color(VictronPalette.TEXT_DIM)

/** Green → yellow-green → orange along the length of the arc, same stops as the watch. */
private val CURRENT_GRADIENT = listOf(
    Color(VictronPalette.CURRENT_LOW),
    Color(VictronPalette.CURRENT_MID),
    Color(VictronPalette.CURRENT_HIGH),
)

/**
 * Battery current: label, value, trend and — replacing the linear bar this used to draw — the same
 * second arc the watch puts in the gap at the bottom of its gauge ring, so the two surfaces speak
 * one visual language.
 *
 * [maxAmps] is the charger's rating (or the highest current seen); `null` means no device yet.
 * [peakFraction] marks the highest current in the trend window with a tick across the track.
 *
 * Unlike the watch, the arc is sign-aware: charging paints the gradient, discharging paints flat
 * `DISCHARGING` orange. Dropping that would lose the one thing the old bar's single hue did say.
 *
 * [sparklineHeight], [maxArcHeight] and [strokeWidth] trade trend and gauge detail for vertical
 * space — a landscape phone has much less of the latter than a portrait one.
 */
@Composable
fun CurrentArcGauge(
    amps: Double?,
    maxAmps: Double?,
    stale: Boolean,
    modifier: Modifier = Modifier,
    series: MetricSeries? = null,
    peakFraction: Float? = null,
    sparklineHeight: Dp = 72.dp,
    maxArcHeight: Dp = 104.dp,
    strokeWidth: Dp = 16.dp,
) {
    val scale = maxAmps ?: FALLBACK_MAX_AMPS
    val fraction = ((abs(amps ?: 0.0) / scale).coerceIn(0.0, 1.0)).toFloat()
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 600),
        label = "current-arc",
    )
    // Same spec as the fill, so a new high and the value that set it move together.
    val animatedPeak by animateFloatAsState(
        targetValue = (peakFraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600),
        label = "current-arc-peak",
    )
    val arcColor = if (stale) TEXT_DIM else Color(VictronPalette.currentColor(amps))
    val charging = (amps ?: 0.0) > 0.05
    val gradient = if (!stale && charging) CURRENT_GRADIENT else null

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

        // Trend above the arc — generous height for prominence.
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

        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val glow = strokeWidth + GLOW_EXTRA
            // The band's shape is fixed, so width and height cannot both be satisfied: take
            // whichever binds first and let the arc give up width rather than turn into an ellipse.
            // A height-bound arc simply gets smaller and centres itself in the row.
            val radius = minOf(
                (maxWidth - glow) / CHORD_FRACTION,
                (maxArcHeight - glow) / SAGITTA_FRACTION,
            ).coerceAtLeast(0.dp)
            val bandHeight = radius * SAGITTA_FRACTION + glow
            val bandWidth = radius * CHORD_FRACTION + glow

            Canvas(modifier = Modifier.width(bandWidth).height(bandHeight)) {
                val glowPx = glow.toPx()
                val r = radius.toPx()
                // The circle's centre sits *above* the band — the mirror image of PvArcGauge, which
                // hangs its circle from the top. Everything angular pivots on ArcSpec.center, so
                // the empty top of the circle simply falls outside the box.
                val spec = ArcSpec(
                    topLeft = Offset(size.width / 2f - r, glowPx / 2f - r * COS_HALF_SWEEP - r),
                    size = Size(r * 2f, r * 2f),
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE,
                    strokeWidth = strokeWidth.toPx(),
                    glowWidth = glowPx,
                )

                drawArcTrack(spec, TRACK)
                drawArcFill(
                    spec = spec,
                    fraction = animated,
                    gradient = gradient,
                    flatColor = arcColor,
                    glowColor = (gradient?.first() ?: arcColor).copy(alpha = 0.25f),
                )
                if (peakFraction != null) {
                    drawPeakTick(
                        spec = spec,
                        peakFraction = animatedPeak,
                        color = if (stale) {
                            TEXT_DIM.copy(alpha = 0.7f)
                        } else {
                            Color(VictronPalette.PEAK_MARKER)
                        },
                    )
                }
            }

            // Full scale, in the empty middle of the bowl. Dropped when the band is too shallow to
            // hold it clear of the stroke.
            if (bandHeight >= LABEL_MIN_BAND_HEIGHT) {
                Text(
                    text = stringResource(R.string.gauge_of_scale_amps, Formatting.amps(scale)),
                    fontSize = 11.sp,
                    color = TEXT_DIM,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = bandHeight * 0.28f),
                )
            }
        }
    }
}
