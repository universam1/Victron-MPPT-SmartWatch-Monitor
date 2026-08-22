package de.universam.victron.mobile.ui.dashboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Thickness of the peak tick, as a share of the arc stroke it crosses. */
private const val TICK_WIDTH_FRACTION = 0.15f

private const val DEGREES_PER_RADIAN = 180f / Math.PI.toFloat()

/**
 * Where one arc lives inside a Canvas: a square box, a sweep, and the two stroke widths.
 *
 * Both phone arcs hang off an edge of their box — the power gauge from the top, the current gauge
 * from the bottom — so [center] is deliberately *not* `DrawScope.center`. Everything angular (the
 * sweep gradient, the rotation that aligns it, the peak tick) pivots here instead.
 */
internal data class ArcSpec(
    val topLeft: Offset,
    /** Square: an extreme window aspect ratio must not turn the gauge into an ellipse. */
    val size: Size,
    val startAngle: Float,
    val sweepAngle: Float,
    val strokeWidth: Float,
    val glowWidth: Float,
) {
    val center: Offset get() = Offset(topLeft.x + size.width / 2f, topLeft.y + size.height / 2f)

    val radius: Float get() = size.width / 2f

    /** Angular size of the dot drawn at zero, so the arc is identifiable when there is no value. */
    val minSweep: Float get() = strokeWidth / radius * DEGREES_PER_RADIAN

    /** How far the glow — and the peak tick — reach past each edge of the stroke. */
    val overhang: Float get() = (glowWidth - strokeWidth) / 2f
}

/** The unfilled part of the gauge. */
internal fun DrawScope.drawArcTrack(spec: ArcSpec, color: Color) {
    drawArc(
        color = color,
        startAngle = spec.startAngle,
        sweepAngle = spec.sweepAngle,
        useCenter = false,
        topLeft = spec.topLeft,
        size = spec.size,
        style = Stroke(width = spec.strokeWidth, cap = StrokeCap.Round),
    )
}

/**
 * The filled part, painted as a gradient *along* the arc when [gradient] has stops and flat in
 * [flatColor] when it does not (the stale case, and a discharging current).
 *
 * The gradient is a [Brush.sweepGradient] inside a `rotate` that puts the arc's start at 0°, which
 * is what aligns the first colour stop with the start of the arc. Its body is drawn with butt caps
 * so the colours are not smeared across the end caps, then round-cap dots are overdrawn at both
 * ends in the right solid colour.
 */
internal fun DrawScope.drawArcFill(
    spec: ArcSpec,
    fraction: Float,
    gradient: List<Color>?,
    flatColor: Color,
    glowColor: Color,
) {
    val roundStroke = Stroke(width = spec.strokeWidth, cap = StrokeCap.Round)
    val clamped = fraction.coerceIn(0f, 1f)

    if (clamped <= 0f) {
        drawArc(
            color = flatColor,
            startAngle = spec.startAngle,
            sweepAngle = spec.minSweep,
            useCenter = false,
            topLeft = spec.topLeft,
            size = spec.size,
            style = roundStroke,
        )
        return
    }

    val sweep = spec.sweepAngle * clamped

    if (gradient == null || gradient.size < 2) {
        drawArc(
            color = glowColor,
            startAngle = spec.startAngle,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = spec.topLeft,
            size = spec.size,
            style = Stroke(width = spec.glowWidth, cap = StrokeCap.Round),
        )
        drawArc(
            color = flatColor,
            startAngle = spec.startAngle,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = spec.topLeft,
            size = spec.size,
            style = roundStroke,
        )
        return
    }

    val arcFraction = spec.sweepAngle / 360f
    val stops = buildList {
        add(0f to gradient.first())
        gradient.forEachIndexed { i, color ->
            if (i > 0) add(i.toFloat() / gradient.lastIndex * arcFraction to color)
        }
        // Pad the remainder with the end colour so nothing leaks back round to the start.
        add(1f to gradient.last())
    }.toTypedArray()
    val brush = Brush.sweepGradient(colorStops = stops, center = spec.center)
    val buttStroke = Stroke(width = spec.strokeWidth, cap = StrokeCap.Butt)
    val glowStroke = Stroke(width = spec.glowWidth, cap = StrokeCap.Round)
    val capAngle = spec.minSweep * 0.1f

    rotate(degrees = spec.startAngle, pivot = spec.center) {
        drawArc(
            color = glowColor,
            startAngle = 0f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = spec.topLeft,
            size = spec.size,
            style = glowStroke,
        )
        drawArc(
            brush = brush,
            startAngle = 0f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = spec.topLeft,
            size = spec.size,
            style = buttStroke,
        )
        drawArc(
            color = gradient.first(),
            startAngle = -capAngle,
            sweepAngle = capAngle * 2f,
            useCenter = false,
            topLeft = spec.topLeft,
            size = spec.size,
            style = roundStroke,
        )
        // Tip cap in the gradient colour the arc has actually reached.
        val position = clamped * gradient.lastIndex
        val low = position.toInt().coerceAtMost(gradient.lastIndex - 1)
        drawArc(
            color = lerp(gradient[low], gradient[low + 1], position - low),
            startAngle = sweep - capAngle,
            sweepAngle = capAngle * 2f,
            useCenter = false,
            topLeft = spec.topLeft,
            size = spec.size,
            style = roundStroke,
        )
    }
}

/**
 * The high-water mark: a thin line drawn radially *across* the track at [peakFraction], overhanging
 * the stroke on both sides so it reads as a scale mark rather than a piece of the fill.
 *
 * Skipped when it would fall inside the dot the arc always draws at its start, where it would only
 * thicken that dot.
 */
internal fun DrawScope.drawPeakTick(spec: ArcSpec, peakFraction: Float, color: Color) {
    val clamped = peakFraction.coerceIn(0f, 1f)
    if (spec.sweepAngle * clamped < spec.minSweep) return

    val radians = Math.toRadians((spec.startAngle + spec.sweepAngle * clamped).toDouble())
    val dx = cos(radians).toFloat()
    val dy = sin(radians).toFloat()
    val inner = spec.radius - spec.strokeWidth / 2f - spec.overhang
    val outer = spec.radius + spec.strokeWidth / 2f + spec.overhang

    drawLine(
        color = color,
        start = Offset(spec.center.x + dx * inner, spec.center.y + dy * inner),
        end = Offset(spec.center.x + dx * outer, spec.center.y + dy * outer),
        strokeWidth = (spec.strokeWidth * TICK_WIDTH_FRACTION).coerceAtLeast(1.5.dp.toPx()),
        cap = StrokeCap.Butt,
    )
}
