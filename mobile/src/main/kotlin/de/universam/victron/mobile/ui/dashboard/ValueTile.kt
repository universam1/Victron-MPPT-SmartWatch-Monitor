package de.universam.victron.mobile.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.model.MetricSeries

private val SURFACE = Color(0xFF121E2E)
private val SURFACE_LIGHT = Color(0xFF1A2940)
private val TEXT_DIM = Color(VictronPalette.TEXT_DIM)
private val TEXT_PRIMARY = Color(VictronPalette.TEXT)

/**
 * A compact card showing one metric: a colored accent strip on the left, a dim label with icon,
 * and a large value. Gradient background and faint accent wash give depth. Optional sparkline
 * drawn behind.
 *
 * [compact] trims the padding and type so a column of tiles fits the height of a landscape phone
 * without scrolling.
 */
@Composable
fun ValueTile(
    label: String,
    value: String,
    accentColor: Color,
    stale: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    series: MetricSeries? = null,
    compact: Boolean = false,
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
        // Sparkline behind content. No span label here: the tile is 28-40.dp tall behind its own
        // value text, and the two prominent trends above already say how far back they reach.
        val points = series?.points.orEmpty()
        if (points.size >= 2) {
            Sparkline(
                values = points,
                color = activeAccent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 28.dp else 40.dp)
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
                .padding(
                    start = if (compact) 12.dp else 16.dp,
                    end = if (compact) 10.dp else 14.dp,
                    top = if (compact) 10.dp else 16.dp,
                    bottom = if (compact) 10.dp else 16.dp,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).padding(end = 4.dp),
                        tint = TEXT_DIM,
                    )
                }
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = TEXT_DIM,
                )
            }
            Text(
                text = value,
                fontSize = if (compact) 18.sp else 28.sp,
                fontWeight = FontWeight.Medium,
                color = if (stale) TEXT_DIM else TEXT_PRIMARY,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}
