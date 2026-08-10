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

/** Where the gauge starts and how far it sweeps — a 240° arc with the gap at the bottom. */
private const val START_ANGLE = 150f
private const val SWEEP_ANGLE = 240f

/**
 * The gauge the whole watch screen is built around: one thick arc for PV power, drawn along the
 * bezel so the middle stays free for the number itself. A glow layer behind the fill arc adds depth
 * on OLED displays.
 */
@Composable
fun PowerArc(
    fraction: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 11.dp,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600),
        label = "pv-arc",
    )

    Canvas(modifier = modifier) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val glowStroke = Stroke(width = strokeWidth.toPx() + 6.dp.toPx(), cap = StrokeCap.Round)
        val inset = (strokeWidth.toPx() + 6.dp.toPx()) / 2f
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)

        drawArc(
            color = trackColor,
            startAngle = START_ANGLE,
            sweepAngle = SWEEP_ANGLE,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        if (animated > 0f) {
            // Glow layer
            drawArc(
                color = color.copy(alpha = 0.25f),
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = glowStroke,
            )
            // Main arc
            drawArc(
                color = color,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
    }
}
