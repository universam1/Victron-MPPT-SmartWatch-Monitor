package de.universam.victron.wear.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

        // Glow layer
        drawArc(
            color = color.copy(alpha = 0.25f),
            startAngle = startAngle,
            sweepAngle = fillSweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = glowStroke,
        )
        // Main arc
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
