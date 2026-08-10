package de.universam.victron.mobile.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * A smooth sparkline with a glowing bleed-out effect. Draws the path three times at decreasing
 * widths and increasing opacity (outer glow → inner glow → crisp line), plus a gradient fill
 * bleeding down from the line.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (values.size < 2) return

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val padding = 2.dp.toPx()
        val drawHeight = height - padding * 2

        // Normalize to 0..1
        val min = values.min()
        val max = values.max()
        val range = (max - min).coerceAtLeast(0.001f)

        fun xFor(i: Int) = i.toFloat() / (values.size - 1) * width
        fun yFor(v: Float) = padding + drawHeight * (1f - (v - min) / range)

        // Build smooth path using quadratic bezier between midpoints
        val path = Path().apply {
            moveTo(xFor(0), yFor(values[0]))
            for (i in 0 until values.size - 1) {
                val x0 = xFor(i)
                val y0 = yFor(values[i])
                val x1 = xFor(i + 1)
                val y1 = yFor(values[i + 1])
                val mx = (x0 + x1) / 2f
                val my = (y0 + y1) / 2f
                quadraticTo(x0, y0, mx, my)
            }
            lineTo(xFor(values.size - 1), yFor(values.last()))
        }

        // Fill path — gradient bleeding down from line
        val fillPath = Path().apply {
            addPath(path)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.15f), Color.Transparent),
            ),
        )

        // Outer glow — wide, faint
        drawPath(
            path = path,
            color = color.copy(alpha = 0.15f),
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Inner glow — medium
        drawPath(
            path = path,
            color = color.copy(alpha = 0.35f),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Crisp line
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
