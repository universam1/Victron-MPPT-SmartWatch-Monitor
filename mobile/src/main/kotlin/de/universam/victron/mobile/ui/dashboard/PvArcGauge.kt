package de.universam.victron.mobile.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.universam.victron.data.Formatting
import de.universam.victron.data.VictronPalette

private const val START_ANGLE = 150f
private const val SWEEP_ANGLE = 240f

private val SOLAR = Color(VictronPalette.SOLAR)
private val SOLAR_GLOW = Color(0xFF_FFD54F).copy(alpha = 0.35f)
private val TRACK = Color(0xFF1A2332)
private val TEXT_DIM = Color(VictronPalette.TEXT_DIM)

/**
 * Large animated arc gauge showing PV power. A glow arc drawn behind the main arc gives depth;
 * the watts value sits centered inside with an optional sparkline trend below.
 */
@Composable
fun PvArcGauge(
    fraction: Float,
    watts: Int?,
    scaleMaxW: Int,
    stale: Boolean,
    sparklineValues: List<Float> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800),
        label = "pv-arc",
    )

    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val strokeWidth = 20.dp.toPx()
            val glowWidth = strokeWidth + 8.dp.toPx()
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            val glowStroke = Stroke(width = glowWidth, cap = StrokeCap.Round)
            val inset = glowWidth / 2f
            val arcSize = Size(size.width - glowWidth, size.height - glowWidth)
            val topLeft = Offset(inset, inset)

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
                val fillColor = if (stale) TEXT_DIM else SOLAR
                val glow = if (stale) Color.Transparent else SOLAR_GLOW

                // Glow layer — wider, translucent
                drawArc(
                    color = glow,
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = glowStroke,
                )

                // Main arc
                drawArc(
                    color = fillColor,
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
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

        // Center content: text + sparkline
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
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
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (stale) TEXT_DIM else Color.White,
                )
            }
            Text(
                text = "of $scaleMaxW W",
                fontSize = 14.sp,
                color = if (stale) TEXT_DIM else SOLAR,
            )
            // Power sparkline inside the arc
            if (sparklineValues.size >= 2) {
                Sparkline(
                    values = sparklineValues,
                    color = if (stale) TEXT_DIM else SOLAR,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(top = 6.dp),
                )
            }
        }
    }
}
