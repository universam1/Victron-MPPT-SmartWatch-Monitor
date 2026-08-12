package de.universam.victron.wear.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Default geometry: a 240° arc with the gap at the bottom (start at 7 o'clock). */
private const val DEFAULT_START_ANGLE = 150f
private const val DEFAULT_SWEEP_ANGLE = 240f

/**
 * The gauge the whole watch screen is built around: one thick arc drawn along the bezel so the
 * middle stays free for the number itself. A glow layer behind the fill arc adds depth on OLED
 * displays.
 *
 * [startAngle] and [sweepAngle] default to a 240° upper arc; pass custom values to draw shorter
 * arcs in the remaining gap (e.g. 120° bottom arc for battery current).
 *
 * Pass [gradientColors] to paint a heat-gradient along the arc (e.g. yellow → orange → red).
 * When null, the arc uses the flat [color].
 */
@Composable
fun PowerArc(
    fraction: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 11.dp,
    startAngle: Float = DEFAULT_START_ANGLE,
    sweepAngle: Float = DEFAULT_SWEEP_ANGLE,
    gradientColors: List<Color>? = null,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600),
        label = "arc",
    )

    Canvas(modifier = modifier) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val glowStroke = Stroke(width = strokeWidth.toPx() + 6.dp.toPx(), cap = StrokeCap.Round)
        val inset = (strokeWidth.toPx() + 6.dp.toPx()) / 2f
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)

        drawArc(
            color = trackColor,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        // Always show at least a small dot at the start so the arc is identifiable even at 0.
        val minSweep = strokeWidth.toPx() / (size.width / 2f) * (180f / Math.PI.toFloat())
        val fillSweep = maxOf(sweepAngle * animated, minSweep)

        if (gradientColors != null && gradientColors.size >= 2) {
            // Build a sweep gradient aligned to the arc by rotating the draw scope so the arc
            // starts at 0° and placing color stops in the 0..sweepAngle/360 range.
            val arcFraction = sweepAngle / 360f
            val stops = buildList {
                add(0f to gradientColors.first())
                gradientColors.forEachIndexed { i, c ->
                    val pos = (i.toFloat() / gradientColors.lastIndex) * arcFraction
                    if (i > 0) add(pos to c)
                }
                // Fill remainder with end color so nothing leaks.
                add(1f to gradientColors.last())
            }.toTypedArray()
            val brush = Brush.sweepGradient(colorStops = stops, center = center)
            val glowColor = gradientColors.first().copy(alpha = 0.25f)

            // Use Butt caps for the gradient to avoid color interpolation at endpoints,
            // then overdraw round-cap dots at start/tip in the correct solid color.
            // Glow keeps Round caps since it's a single color (no interpolation issue).
            val buttStroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Butt)
            val glowRoundStroke = Stroke(width = strokeWidth.toPx() + 6.dp.toPx(), cap = StrokeCap.Round)
            val capRadius = arcSize.width / 2f
            val capAngle = strokeWidth.toPx() / capRadius * (180f / Math.PI.toFloat()) * 0.1f

            rotate(degrees = startAngle, pivot = center) {
                drawArc(
                    color = glowColor,
                    startAngle = 0f,
                    sweepAngle = fillSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = glowRoundStroke,
                )
                drawArc(
                    brush = brush,
                    startAngle = 0f,
                    sweepAngle = fillSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = buttStroke,
                )
                // Start cap in start color
                drawArc(
                    color = gradientColors.first(),
                    startAngle = -capAngle,
                    sweepAngle = capAngle * 2,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
                )
                // End cap — lerp color based on fill position in the gradient
                val tipFraction = (fillSweep / sweepAngle).coerceIn(0f, 1f)
                val idx = tipFraction * (gradientColors.lastIndex)
                val lo = idx.toInt().coerceAtMost(gradientColors.lastIndex - 1)
                val tipColor = androidx.compose.ui.graphics.lerp(
                    gradientColors[lo], gradientColors[lo + 1], idx - lo,
                )
                drawArc(
                    color = tipColor,
                    startAngle = fillSweep - capAngle,
                    sweepAngle = capAngle * 2,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
                )
            }
        } else {
            // Flat color path
            drawArc(
                color = color.copy(alpha = 0.25f),
                startAngle = startAngle,
                sweepAngle = fillSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = glowStroke,
            )
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = fillSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
    }
}
