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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.universam.victron.data.Formatting
import de.universam.victron.data.VictronPalette
import de.universam.victron.mobile.R

private const val START_ANGLE = 150f
private const val SWEEP_ANGLE = 240f

/**
 * Height the gauge reserves, as a fraction of its circle's diameter. The 240° arc opens downwards:
 * its tips sit at `sin(30°) = 0.5·r` below the centre, so the bottom quarter of a square box is
 * always empty. Reserving 0.8 instead of 1.0 keeps that dead band out of the layout — which is what
 * lets the tiles below fit on a phone portrait screen — while leaving the round caps and their glow
 * room below the tips. The circle is drawn from the top of the box, not centred in it.
 */
private const val ARC_HEIGHT_FRACTION = 0.8f

private val SOLAR = Color(VictronPalette.SOLAR)
private val TRACK = Color(0xFF1A2332)
private val TEXT_DIM = Color(VictronPalette.TEXT_DIM)

/**
 * Large animated arc gauge showing PV power. A glow arc drawn behind the main arc gives depth;
 * the watts value sits centered inside with an optional sparkline trend below.
 *
 * The gauge is a circle that reserves only [ARC_HEIGHT_FRACTION] of its diameter in height, because
 * the arc leaves the bottom of its square empty. Pass [matchHeightFirst] when the height is the
 * constrained dimension — in a landscape two-column layout, deriving the circle from the available
 * *width* would make it taller than the screen. Type sizes inside follow the resolved size, so the
 * same composable works at the full width of a portrait phone and at the height of a landscape one.
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
    sparklineValues: List<Float> = emptyList(),
    matchHeightFirst: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800),
        label = "pv-arc",
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
            val strokeWidth = 20.dp.toPx()
            val glowWidth = strokeWidth + 8.dp.toPx()
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            val glowStroke = Stroke(width = glowWidth, cap = StrokeCap.Round)
            val inset = glowWidth / 2f
            // Draw a circle in whatever box we got: an extreme window aspect ratio must not turn
            // the gauge into an ellipse. It hangs from the top edge, so the part left outside the
            // box is the empty bottom of the arc.
            val diameterPx = minOf(size.width, size.height / ARC_HEIGHT_FRACTION)
            val arcSize = Size(diameterPx - glowWidth, diameterPx - glowWidth)
            val topLeft = Offset((size.width - diameterPx) / 2f + inset, inset)
            // The circle's centre, which is below the box centre now that it hangs from the top.
            // The sweep gradient and the rotation that aligns it to START_ANGLE both pivot here.
            val arcCenter = Offset(topLeft.x + arcSize.width / 2f, topLeft.y + arcSize.height / 2f)

            // Track — slightly blue-tinted for depth
            drawArc(
                color = TRACK,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )

            if (animated > 0f) {
                if (stale) {
                    // Stale: flat dim color, no gradient
                    drawArc(
                        color = TEXT_DIM,
                        startAngle = START_ANGLE,
                        sweepAngle = SWEEP_ANGLE * animated,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = stroke,
                    )
                } else {
                    // Heat gradient: white → yellow → orange → red along the arc
                    val arcFraction = SWEEP_ANGLE / 360f
                    val heatLow = Color(VictronPalette.HEAT_LOW)
                    val heatMidLow = Color(VictronPalette.HEAT_MID_LOW)
                    val heatMid = Color(VictronPalette.HEAT_MID)
                    val heatHigh = Color(VictronPalette.HEAT_HIGH)
                    val stops = arrayOf(
                        0f to heatLow,
                        arcFraction * 0.33f to heatMidLow,
                        arcFraction * 0.66f to heatMid,
                        arcFraction to heatHigh,
                        1f to heatHigh,
                    )
                    val brush = Brush.sweepGradient(colorStops = stops, center = arcCenter)
                    val glowColor = heatMidLow.copy(alpha = 0.35f)

                    // Butt caps for gradient + round-cap dots at endpoints for clean edges.
                    // Glow keeps Round caps since it's a single color.
                    val buttStroke = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                    val glowRoundStroke = Stroke(width = glowWidth, cap = StrokeCap.Round)
                    val capRadius = arcSize.width / 2f
                    val capAngle = strokeWidth / capRadius * (180f / Math.PI.toFloat()) * 0.1f

                    rotate(degrees = START_ANGLE, pivot = arcCenter) {
                        drawArc(
                            color = glowColor,
                            startAngle = 0f,
                            sweepAngle = SWEEP_ANGLE * animated,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = glowRoundStroke,
                        )
                        drawArc(
                            brush = brush,
                            startAngle = 0f,
                            sweepAngle = SWEEP_ANGLE * animated,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = buttStroke,
                        )
                        // Start cap
                        drawArc(
                            color = heatLow,
                            startAngle = -capAngle,
                            sweepAngle = capAngle * 2,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        )
                        // End cap — lerp color at the tip position
                        val tipFrac = animated.coerceIn(0f, 1f)
                        val tipColor = when {
                            tipFrac < 0.33f -> androidx.compose.ui.graphics.lerp(heatLow, heatMidLow, tipFrac / 0.33f)
                            tipFrac < 0.66f -> androidx.compose.ui.graphics.lerp(heatMidLow, heatMid, (tipFrac - 0.33f) / 0.33f)
                            else -> androidx.compose.ui.graphics.lerp(heatMid, heatHigh, (tipFrac - 0.66f) / 0.34f)
                        }
                        drawArc(
                            color = tipColor,
                            startAngle = SWEEP_ANGLE * animated - capAngle,
                            sweepAngle = capAngle * 2,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        )
                    }
                }
            } else {
                // Minimal dot at the start so the arc is identifiable even at 0.
                val fillColor = if (stale) TEXT_DIM else SOLAR
                val minSweep = strokeWidth / (arcSize.width / 2f) * (180f / Math.PI.toFloat())
                drawArc(
                    color = fillColor,
                    startAngle = START_ANGLE,
                    sweepAngle = minSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }

        // Center content: text + sparkline. Offset to the circle's centre — the box is shorter than
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
            // Power sparkline inside the arc — dropped when the gauge is too small to fit it.
            if (sparklineValues.size >= 2 && diameter >= 220.dp) {
                Sparkline(
                    values = sparklineValues,
                    color = if (stale) TEXT_DIM else SOLAR,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((diameter * 0.1f).coerceAtMost(36.dp))
                        .padding(top = 6.dp),
                )
            }
        }
    }
}
