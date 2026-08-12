package de.universam.victron.mobile.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.LoadOutputResult
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
 * triggers navigation to setup — swipe past the last device to open settings, or use the gear
 * button, which works even when nothing has been found yet.
 *
 * With no decoded device there is still one page: the dashboard renders with `–` everywhere so the
 * screen and its navigation work without a Victron in range.
 */
@Composable
fun DashboardScreen(
    snapshots: List<DeviceSnapshot>,
    config: AppConfig,
    now: Long,
    history: Map<String, ReadingHistory>,
    onOpenSetup: () -> Unit,
    loadOutputResult: LoadOutputResult = LoadOutputResult.Idle,
    onToggleLoadOutput: ((String, Boolean) -> Unit)? = null,
) {
    val decoded = snapshots.filter { it.status == SnapshotStatus.DECODED }
    val bgBrush = Brush.verticalGradient(listOf(BG_TOP, BG_BOTTOM))
    // One page per device — plus a placeholder page when there is no device yet — and one final
    // "settings" page that only exists to make a swipe past the end open setup.
    val devicePages = decoded.size.coerceAtLeast(1)
    val pagerState = rememberPagerState { devicePages + 1 }

    // When the user lands on the settings page, open setup
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == devicePages) onOpenSetup()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush),
    ) {
        // The gradient stays full-bleed behind the system bars; the content does not. In landscape
        // that is what keeps the gauge and the gear button clear of the navigation bar and a
        // display cutout.
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                if (page < devicePages) {
                    val snapshot = decoded.getOrNull(page)
                    DeviceDashboard(
                        snapshot = snapshot,
                        peakWatts = snapshot?.let { config.pvPeakWattsFor(it.address) } ?: 0,
                        now = now,
                        modifier = Modifier.fillMaxSize(),
                        history = snapshot?.let { history[it.address.uppercase()] },
                        // Always-available way into setup, so navigation never depends on finding a
                        // device first.
                        onOpenSetup = onOpenSetup,
                        loadOutputOn = when (loadOutputResult) {
                            is LoadOutputResult.Done -> loadOutputResult.isOn
                            else -> snapshot?.solarCharger?.loadCurrent?.let { true }
                        },
                        loadOutputSending = loadOutputResult is LoadOutputResult.Sending,
                        onToggleLoadOutput = snapshot?.let { s ->
                            onToggleLoadOutput?.let { cb -> { enabled: Boolean -> cb(s.address, enabled) } }
                        },
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
}
