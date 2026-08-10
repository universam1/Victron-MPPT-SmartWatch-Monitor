package de.universam.victron.mobile.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.model.AppConfig
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.ReadingHistory
import de.universam.victron.data.model.SnapshotStatus
import de.universam.victron.mobile.R

private val BG_TOP = Color(0xFF0A1628)
private val BG_BOTTOM = Color(0xFF0D1F3C)
private val SOLAR = Color(VictronPalette.SOLAR)
private val TRACK = Color(0xFF1A2332)
private val TEXT_DIM = Color(VictronPalette.TEXT_DIM)

/**
 * Top-level dashboard: pages through decoded devices with a horizontal pager. Shows page indicators
 * when there are multiple devices, and a small setup button in the bottom corner.
 */
@Composable
fun DashboardScreen(
    snapshots: List<DeviceSnapshot>,
    config: AppConfig,
    now: Long,
    history: Map<String, ReadingHistory>,
    onOpenSetup: () -> Unit,
) {
    val decoded = snapshots.filter { it.status == SnapshotStatus.DECODED }
    val bgBrush = Brush.verticalGradient(listOf(BG_TOP, BG_BOTTOM))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush),
    ) {
            if (decoded.size == 1) {
                val snapshot = decoded[0]
                DeviceDashboard(
                    snapshot = snapshot,
                    peakWatts = config.pvPeakWattsFor(snapshot.address),
                    now = now,
                    modifier = Modifier.fillMaxSize(),
                    history = history[snapshot.address.uppercase()],
                )
            } else if (decoded.size > 1) {
                val pagerState = rememberPagerState { decoded.size }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val snapshot = decoded[page]
                    DeviceDashboard(
                        snapshot = snapshot,
                        peakWatts = config.pvPeakWattsFor(snapshot.address),
                        now = now,
                        modifier = Modifier.fillMaxSize(),
                        history = history[snapshot.address.uppercase()],
                    )
                }
                // Page indicators
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(decoded.size) { index ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == pagerState.currentPage) SOLAR else TRACK,
                                ),
                        )
                    }
                }
            }

            TextButton(
                onClick = onOpenSetup,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            ) {
                Text(
                    text = "⚙ ${stringResource(R.string.dashboard_setup)}",
                    color = TEXT_DIM,
                )
            }
    }
}
