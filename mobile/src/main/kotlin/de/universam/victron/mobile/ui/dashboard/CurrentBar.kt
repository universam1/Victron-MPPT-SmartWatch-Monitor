package de.universam.victron.mobile.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.universam.victron.data.Formatting
import de.universam.victron.data.VictronPalette
import de.universam.victron.mobile.R
import kotlin.math.abs

private const val MAX_AMPS = 30f
private val TRACK = Color(0xFF1A2332)
private val TEXT_DIM = Color(VictronPalette.TEXT_DIM)

/**
 * Horizontal animated bar gauge for battery current. Green when charging, orange when discharging,
 * dimmed when stale. Includes a glow layer and vertical gradient for depth, plus optional sparkline.
 */
@Composable
fun CurrentBar(
    amps: Double?,
    stale: Boolean,
    modifier: Modifier = Modifier,
    sparklineValues: List<Float> = emptyList(),
) {
    val fraction = ((abs(amps ?: 0.0) / MAX_AMPS).coerceIn(0.0, 1.0)).toFloat()
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 600),
        label = "current-bar",
    )
    val barColor = if (stale) TEXT_DIM else Color(VictronPalette.currentColor(amps))

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(R.string.label_battery_current),
                fontSize = 14.sp,
                color = TEXT_DIM,
            )
            Text(
                text = Formatting.amps(amps),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = barColor,
            )
        }

        // Sparkline above the bar — double height for prominence
        if (sparklineValues.size >= 2) {
            Sparkline(
                values = sparklineValues,
                color = barColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            )
        }

        Box(modifier = Modifier.fillMaxWidth().height(32.dp).padding(vertical = 2.dp)) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val corner = CornerRadius(size.height / 2f, size.height / 2f)

                // Track
                drawRoundRect(
                    color = TRACK,
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                    cornerRadius = corner,
                )

                if (animated > 0f) {
                    val fillWidth = size.width * animated
                    val glowColor = barColor.copy(alpha = 0.25f)

                    // Glow layer
                    drawRoundRect(
                        color = glowColor,
                        topLeft = Offset(0f, 2.dp.toPx()),
                        size = Size(fillWidth, size.height),
                        cornerRadius = corner,
                    )

                    // Fill with vertical gradient
                    val brush = Brush.verticalGradient(
                        colors = listOf(barColor.copy(alpha = 0.7f), barColor),
                    )
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset.Zero,
                        size = Size(fillWidth, size.height),
                        cornerRadius = corner,
                    )
                } else {
                    // Minimal pill at the start so the bar is identifiable even at 0.
                    val minWidth = size.height // a circle (width == height of the bar)
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset.Zero,
                        size = Size(minWidth, size.height),
                        cornerRadius = corner,
                    )
                }
            }
        }
    }
}
