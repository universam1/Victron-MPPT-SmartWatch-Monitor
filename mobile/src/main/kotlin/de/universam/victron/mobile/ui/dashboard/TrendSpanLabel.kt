package de.universam.victron.mobile.ui.dashboard

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import de.universam.victron.data.Formatting
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.model.MetricSeries
import de.universam.victron.mobile.R

private val TEXT_DIM = Color(VictronPalette.TEXT_DIM)

/**
 * How far back the trend beside it reaches. The window grows for as long as the app keeps
 * collecting readings, so without this there is nothing to say whether a curve is a minute or a
 * morning — and the age in the header answers a different question.
 *
 * Renders nothing until there is a span to report, which keeps it out of the first second after a
 * cold start. Kept as its own composable so the two surfaces that show it cannot word or size it
 * differently.
 */
@Composable
internal fun TrendSpanLabel(
    series: MetricSeries?,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 10.sp,
) {
    val span = series?.spanMillis ?: return
    if (span <= 0L) return

    Text(
        text = stringResource(R.string.trend_span, Formatting.duration(span)),
        fontSize = fontSize,
        color = TEXT_DIM,
        maxLines = 1,
        modifier = modifier,
    )
}
