package de.universam.victron.mobile.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.universam.victron.data.VictronPalette

private val SURFACE = Color(0xFF121E2E)
private val SURFACE_LIGHT = Color(0xFF1A2940)
private val TEXT_DIM = Color(VictronPalette.TEXT_DIM)
private val TEXT_PRIMARY = Color(VictronPalette.TEXT)

/**
 * A compact card showing one metric: a colored accent strip on the left, a dim label, and a large
 * value. Gradient background and faint accent wash give depth. Optional sparkline drawn behind.
 */
@Composable
fun ValueTile(
    label: String,
    value: String,
    accentColor: Color,
    stale: Boolean,
    modifier: Modifier = Modifier,
    sparklineValues: List<Float> = emptyList(),
) {
    val activeAccent = if (stale) TEXT_DIM else accentColor
    val bgBrush = Brush.verticalGradient(
        colors = listOf(SURFACE_LIGHT, SURFACE),
    )
    val washColor = activeAccent.copy(alpha = 0.08f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgBrush)
            .background(washColor),
    ) {
        // Sparkline behind content
        if (sparklineValues.size >= 2) {
            Sparkline(
                values = sparklineValues,
                color = activeAccent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .align(Alignment.BottomCenter),
            )
        }

        // Accent strip
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(5.dp)
                .fillMaxHeight()
                .background(activeAccent),
        )
        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 14.dp, top = 16.dp, bottom = 16.dp),
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = TEXT_DIM,
            )
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = if (stale) TEXT_DIM else TEXT_PRIMARY,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
