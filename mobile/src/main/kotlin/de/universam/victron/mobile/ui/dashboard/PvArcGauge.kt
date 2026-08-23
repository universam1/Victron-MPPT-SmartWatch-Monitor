package de.universam.victron.mobile.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.universam.victron.data.Formatting
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.model.MetricSeries
import de.universam.victron.mobile.R

private const val START_ANGLE = 155f
private const val SWEEP_ANGLE = 230f

/** The battery current arc shares this circle — same geometry as on the watch. */
private const val CURRENT_START_ANGLE = 45f
private const val CURRENT_SWEEP_ANGLE = 90f

/**
 * Height the gauge reserves, as a fraction of its circle's diameter. Both arcs together form a
 * near-complete ring: the PV arc (230°) fills the top and the current arc (90°) fills the bottom
 * gap. The current arc's lowest point is at 90° (full radius below centre), so the box must be
 * nearly square. 0.96 leaves a small margin for the current arc's glow at the nadir while keeping
 * a slight height saving vs. a full square.
 */
private const val ARC_HEIGHT_FRACTION = 0.96f

/** Smallest gauge that still has room for a trend inside the arc. */
private val SPARKLINE_MIN_DIAMETER = 220.dp

private val SOLAR = Color(VictronPalette.SOLAR)
private val TRACK = Color(0xFF1A2332)
private val TEXT_DIM = Color(VictronPalette.TEXT_DIM)
private val DISCHARGING = Color(VictronPalette.DISCHARGING)

/** White → SOLAR yellow → dark orange → fire-red along the length of the arc. */
private val HEAT_GRADIENT = listOf(
    Color(VictronPalette.HEAT_LOW),
    Color(VictronPalette.HEAT_MID_LOW),
    Color(VictronPalette.HEAT_MID),
    Color(VictronPalette.HEAT_HIGH),
)

/** Green → yellow-green → orange along the length of the current arc. */
private val CURRENT_GRADIENT = listOf(
    Color(VictronPalette.CURRENT_LOW),
    Color(VictronPalette.CURRENT_MID),
    Color(VictronPalette.CURRENT_HIGH),
)

/**
 * Large animated arc gauge showing PV power and battery current on the same circle — matching the
 * watch's ring layout where the 230° PV arc and the 90° current arc share the same circle with 20° gaps.
 *
 * [peakFraction] marks the highest power in the trend window with a tick across the track — see
 * `DeviceSnapshot.pvPeakFraction`, which scales it by the same full scale as [fraction] so the tick
 * and the fill cannot disagree. `null` means there is nothing to mark yet.
 *
 * The gauge is a circle that reserves only [ARC_HEIGHT_FRACTION] of its diameter in height. Pass
 * [matchHeightFirst] when the height is the constrained dimension — in a landscape two-column
 * layout, deriving the circle from the available *width* would make it taller than the screen.
 * Type sizes inside follow the resolved size, so the same composable works at the full width of a
 * portrait phone and at the height of a landscape one.
 *
 * Give it at most one fixed dimension: the aspect ratio picks whichever of width and height keeps
 * the circle inside its box, and a `fillMaxSize`/`fillMaxHeight` that fixes both leaves it no
 * choice but to overflow.
 */
@Composable
fun PvArcGauge(
    fraction: Float,
    watts: Int?,
    scaleMaxW: Int,
    stale: Boolean,
    series: MetricSeries? = null,
    peakFraction: Float? = null,
    matchHeightFirst: Boolean = false,
    currentFraction: Float = 0f,
    currentStale: Boolean = true,
    currentCharging: Boolean = false,
    currentPeakFraction: Float? = null,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800),
        label = "pv-arc",
    )
    val animatedPeak by animateFloatAsState(
        targetValue = (peakFraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800),
        label = "pv-arc-peak",
    )
    val animatedCurrent by animateFloatAsState(
        targetValue = currentFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800),
        label = "current-arc",
    )
    val animatedCurrentPeak by animateFloatAsState(
        targetValue = (currentPeakFraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800),
        label = "current-arc-peak",
    )

    BoxWithConstraints(
        modifier = modifier.aspectRatio(
            ratio = 1f / ARC_HEIGHT_FRACTION,
            matchHeightConstraintsFirst = matchHeightFirst,
        ),
        contentAlignment = Alignment.Center,
    ) {
        val diameter = minOf(maxWidth, maxHeight / ARC_HEIGHT_FRACTION)
        val valueFontSize = (diameter.value * 0.16f).coerceIn(30f, 56f).sp
        val scaleFontSize = (diameter.value * 0.04f).coerceIn(11f, 14f).sp
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val diameterPx = minOf(size.width, size.height / ARC_HEIGHT_FRACTION)
            val strokeWidth = (diameterPx * 0.055f).coerceIn(16.dp.toPx(), 28.dp.toPx())
            val glowWidth = strokeWidth + 8.dp.toPx()
            val inset = glowWidth / 2f
            // Draw a circle in whatever box we got: an extreme window aspect ratio must not turn
            // the gauge into an ellipse. It hangs from the top edge, so the part left outside the
            // box is the empty bottom of the arc — which is why ArcSpec.center, and not
            // DrawScope.center, is what everything angular pivots on.
            val spec = ArcSpec(
                topLeft = Offset((size.width - diameterPx) / 2f + inset, inset),
                size = Size(diameterPx - glowWidth, diameterPx - glowWidth),
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                strokeWidth = strokeWidth,
                glowWidth = glowWidth,
            )

            drawArcTrack(spec, TRACK)
            drawArcFill(
                spec = spec,
                fraction = animated,
                // Stale: flat dim colour, no heat gradient.
                gradient = if (stale) null else HEAT_GRADIENT,
                flatColor = if (stale) TEXT_DIM else SOLAR,
                // The *second* stop, not the first: the first is white and a white glow washes the
                // whole arc out.
                glowColor = if (stale) {
                    TEXT_DIM.copy(alpha = 0.25f)
                } else {
                    Color(VictronPalette.HEAT_MID_LOW).copy(alpha = 0.35f)
                },
            )
            if (peakFraction != null) {
                drawPeakTick(
                    spec = spec,
                    peakFraction = animatedPeak,
                    color = if (stale) TEXT_DIM.copy(alpha = 0.7f) else Color(VictronPalette.PEAK_MARKER),
                )
            }

            // Battery current arc — shares the same circle, fills the gap at the bottom.
            val currentStrokePx = strokeWidth * 0.7f
            val currentGlowPx = currentStrokePx + 8.dp.toPx()
            val currentSpec = ArcSpec(
                topLeft = spec.topLeft,
                size = spec.size,
                startAngle = CURRENT_START_ANGLE,
                sweepAngle = CURRENT_SWEEP_ANGLE,
                strokeWidth = currentStrokePx,
                glowWidth = currentGlowPx,
            )

            val currentGradient = if (currentStale) null else if (currentCharging) CURRENT_GRADIENT else null
            val currentFlatColor = when {
                currentStale -> TEXT_DIM
                currentCharging -> CURRENT_GRADIENT.first()
                else -> DISCHARGING
            }
            val currentGlowColor = when {
                currentStale -> TEXT_DIM.copy(alpha = 0.25f)
                currentCharging -> CURRENT_GRADIENT.first().copy(alpha = 0.25f)
                else -> DISCHARGING.copy(alpha = 0.25f)
            }

            drawArcTrack(currentSpec, TRACK)
            drawArcFill(
                spec = currentSpec,
                fraction = animatedCurrent,
                gradient = currentGradient,
                flatColor = currentFlatColor,
                glowColor = currentGlowColor,
            )
            if (currentPeakFraction != null) {
                drawPeakTick(
                    spec = currentSpec,
                    peakFraction = animatedCurrentPeak,
                    color = if (currentStale) TEXT_DIM.copy(alpha = 0.7f) else Color(VictronPalette.PEAK_MARKER),
                )
            }
        }

        // Center content: text + trend. Offset to the circle's centre — the box is shorter than
        // the circle, so its own centre sits above it.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset(y = diameter * (1f - ARC_HEIGHT_FRACTION) / 2f)
                .padding(horizontal = diameter * 0.13f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.WbSunny,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp).padding(end = 4.dp),
                    tint = if (stale) TEXT_DIM else SOLAR,
                )
                Text(
                    text = Formatting.watts(watts),
                    fontSize = valueFontSize,
                    fontWeight = FontWeight.Bold,
                    color = if (stale) TEXT_DIM else Color.White,
                    maxLines = 1,
                )
            }
            Text(
                text = stringResource(R.string.gauge_of_scale, scaleMaxW),
                fontSize = scaleFontSize,
                color = if (stale) TEXT_DIM else SOLAR,
                maxLines = 1,
            )
            // Power trend inside the arc — dropped when the gauge is too small to fit it.
            val points = series?.points.orEmpty()
            if (points.size >= 2 && diameter >= SPARKLINE_MIN_DIAMETER) {
                Sparkline(
                    values = points,
                    color = if (stale) TEXT_DIM else SOLAR,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((diameter * 0.1f).coerceAtMost(36.dp))
                        .padding(top = 6.dp),
                )
                TrendSpanLabel(series = series)
            }
        }
    }
}
