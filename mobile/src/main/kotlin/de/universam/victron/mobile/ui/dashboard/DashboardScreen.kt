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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.model.AppConfig
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.ReadingHistory
import de.universam.victron.data.model.SnapshotStatus

private val BG_TOP = Color(0xFF0A1628)
private val BG_BOTTOM = Color(0xFF0D1F3C)
private val SOLAR = Color(VictronPalette.SOLAR)
private val TRACK = Color(0xFF1A2332)

/**
 * Top-level dashboard: pages through decoded devices with a horizontal pager. The last page
 * triggers navigation to setup — swipe past the last device to open settings.
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
    // Pages: one per device + one final "settings" page
    val pageCount = decoded.size + 1
    val pagerState = rememberPagerState { pageCount }

    // When the user lands on the settings page, open setup
    androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
        if (decoded.isNotEmpty() && pagerState.currentPage == decoded.size) {
            onOpenSetup()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            if (page < decoded.size) {
                val snapshot = decoded[page]
                DeviceDashboard(
                    snapshot = snapshot,
                    peakWatts = config.pvPeakWattsFor(snapshot.address),
                    now = now,
                    modifier = Modifier.fillMaxSize(),
                    history = history[snapshot.address.uppercase()],
                )
            } else {
                // Empty placeholder — LaunchedEffect navigates away immediately
                Box(modifier = Modifier.fillMaxSize())
            }
        }

        // Page indicators (only device pages, not the settings trigger)
        if (decoded.size > 1) {
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
    }
}
