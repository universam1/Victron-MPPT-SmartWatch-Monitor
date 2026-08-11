package de.universam.victron.wear.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import de.universam.victron.data.Formatting
import de.universam.victron.data.ScanState
import de.universam.victron.data.ScanUnavailable
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.VictronViewModel
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.SnapshotStatus
import de.universam.victron.wear.R

/**
 * Full-bezel power gauge that scrolls down into a detail list — replaces the old HeroScreen +
 * DetailScreen two-screen pattern with a single scrollable surface.
 */
@Composable
fun HeroScreen(
    viewModel: VictronViewModel,
    onOpenDevices: () -> Unit,
) {
    ScanWhileVisible(viewModel)

    val snapshots = collect(viewModel.snapshots)
    val config = collect(viewModel.config)
    val scanState = collect(viewModel.scanState)
    val now = rememberNow()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.retryScan() }

    val decoded = snapshots.filter { it.status == SnapshotStatus.DECODED }
    var index by remember { mutableIntStateOf(0) }
    val selected = decoded.getOrNull(index.coerceIn(0, (decoded.size - 1).coerceAtLeast(0)))

    val pagerState = rememberPagerState { 2 }

    // Swipe to page 1 → open settings
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1) {
            onOpenDevices()
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(VictronPalette.BACKGROUND)),
    ) { page ->
        if (page == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    scanState is ScanState.Unavailable -> BlockedState(
                        reason = scanState.reason,
                        onGrantPermission = { permissionLauncher.launch(BLUETOOTH_SCAN_PERMISSION) },
                        onOpenDevices = onOpenDevices,
                    )

                    selected == null -> EmptyState(
                        scanning = scanState == ScanState.Scanning,
                        onOpenDevices = onOpenDevices,
                    )

                    else -> GaugeList(
                        snapshot = selected,
                        peakWatts = config.pvPeakWattsFor(selected.address),
                        batteryCurrentMax = config.batteryCurrentMaxFor(selected.address),
                        now = now,
                        deviceCount = decoded.size,
                        onCycleDevice = { index = (index + 1) % decoded.size },
                    )
                }
            }
        } else {
            // Empty placeholder — LaunchedEffect navigates away immediately
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun GaugeList(
    snapshot: DeviceSnapshot,
    peakWatts: Int,
    batteryCurrentMax: Double,
    now: Long,
    deviceCount: Int,
    onCycleDevice: () -> Unit,
) {
    val values = snapshot.solarCharger
    val stale = Formatting.isStale(snapshot, now)
    val solar = if (stale) Color(VictronPalette.TEXT_DIM) else Color(VictronPalette.SOLAR)
    val currentColor = if (stale) {
        Color(VictronPalette.TEXT_DIM)
    } else {
        Color(VictronPalette.currentColor(values?.batteryCurrent))
    }

    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(0.dp),
    ) {
        // ── First item: fullscreen gauge with arcs ──
        item {
            GaugeFace(
                snapshot = snapshot,
                peakWatts = peakWatts,
                batteryCurrentMax = batteryCurrentMax,
                now = now,
                solar = solar,
                currentColor = currentColor,
                deviceCount = deviceCount,
                onCycleDevice = onCycleDevice,
            )
        }

        // ── Detail rows as Wear M3 Buttons ──
        item {
            DetailButton(
                icon = Icons.Filled.BatteryChargingFull,
                label = stringResource(R.string.label_battery),
                value = Formatting.volts(values?.batteryVoltage),
                valueColor = Color(VictronPalette.BATTERY),
            )
        }
        item {
            DetailButton(
                icon = Icons.Filled.WbSunny,
                label = stringResource(R.string.label_pv),
                value = Formatting.watts(values?.batteryPowerW),
                valueColor = Color(VictronPalette.SOLAR),
            )
        }
        item {
            DetailButton(
                icon = Icons.Filled.PowerSettingsNew,
                label = stringResource(R.string.label_state),
                value = values?.chargerStateLabel ?: Formatting.PLACEHOLDER,
                valueColor = if (values?.hasError == true) {
                    Color(VictronPalette.ERROR)
                } else {
                    Color(VictronPalette.TEXT_DIM)
                },
            )
        }
        if (values?.hasError == true) {
            item {
                DetailButton(
                    icon = Icons.Filled.Warning,
                    label = stringResource(R.string.label_error),
                    value = values.chargerErrorLabel ?: "Err ${values.chargerErrorCode}",
                    valueColor = Color(VictronPalette.ERROR),
                )
            }
        }
        item {
            DetailButton(
                icon = Icons.Filled.WbSunny,
                label = stringResource(R.string.label_yield_today),
                value = Formatting.energy(values?.yieldTodayWh),
                valueColor = Color(VictronPalette.YIELD),
            )
        }
        if (values?.loadCurrent != null) {
            item {
                DetailButton(
                    icon = Icons.Filled.Settings,
                    label = stringResource(R.string.label_load),
                    value = Formatting.amps(values.loadCurrent),
                    valueColor = Color(VictronPalette.TEXT),
                )
            }
        }
        item {
            DetailButton(
                icon = Icons.Filled.Wifi,
                label = stringResource(R.string.label_signal),
                value = "${snapshot.rssi} dBm",
                valueColor = Color(VictronPalette.TEXT_DIM),
            )
        }
        item {
            DetailButton(
                icon = Icons.Filled.Info,
                label = stringResource(R.string.label_model),
                value = snapshot.modelName,
                valueColor = Color(VictronPalette.TEXT_DIM),
            )
        }
        item {
            DetailButton(
                icon = Icons.Filled.Schedule,
                label = stringResource(R.string.label_age),
                value = Formatting.age(snapshot, now),
                valueColor = Color(VictronPalette.TEXT_DIM),
            )
        }
    }
}

/** The gauge face — arcs + large watts/amps. Extracted so previews reuse the real UI. */
@Composable
internal fun GaugeFace(
    snapshot: DeviceSnapshot,
    peakWatts: Int,
    batteryCurrentMax: Double,
    now: Long,
    solar: Color,
    currentColor: Color,
    deviceCount: Int = 1,
    onCycleDevice: () -> Unit = {},
) {
    val values = snapshot.solarCharger
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(VictronPalette.BACKGROUND)),
        contentAlignment = Alignment.Center,
    ) {
        PowerArc(
            fraction = snapshot.pvFraction(peakWatts),
            color = solar,
            trackColor = Color(VictronPalette.TRACK),
            modifier = Modifier.fillMaxSize(),
        )
        PowerArc(
            fraction = snapshot.batteryCurrentFraction(batteryCurrentMax),
            color = currentColor,
            trackColor = Color(VictronPalette.TRACK),
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 11.dp,
            startAngle = 38f,
            sweepAngle = 104f,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (deviceCount > 1) "${snapshot.displayName}  ›" else snapshot.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = Color(VictronPalette.TEXT_DIM),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = deviceCount > 1, onClick = onCycleDevice)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )

            Row(verticalAlignment = Alignment.Bottom) {
                Icon(
                    imageVector = Icons.Filled.WbSunny,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).padding(end = 2.dp, bottom = 8.dp),
                    tint = solar,
                )
                Text(
                    text = values?.pvPowerW?.toString() ?: Formatting.PLACEHOLDER,
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 48.sp),
                    color = solar,
                    maxLines = 1,
                )
                Text(
                    text = " W",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(VictronPalette.TEXT_DIM),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Icon(
                    imageVector = Icons.Filled.BatteryChargingFull,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).padding(end = 2.dp, bottom = 5.dp),
                    tint = currentColor,
                )
                Text(
                    text = values?.batteryCurrent?.let {
                        String.format(java.util.Locale.US, "%.1f", it)
                    } ?: Formatting.PLACEHOLDER,
                    style = MaterialTheme.typography.displayMedium,
                    color = currentColor,
                    maxLines = 1,
                )
                Text(
                    text = " A",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(VictronPalette.TEXT_DIM),
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }

            Text(
                text = Formatting.age(snapshot, now),
                style = MaterialTheme.typography.labelSmall,
                color = Color(VictronPalette.TEXT_DIM),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** A Wear M3 Button styled as a detail row: icon + label on the left, value on the right. */
@Composable
private fun DetailButton(icon: ImageVector, label: String, value: String, valueColor: Color) {
    Button(
        onClick = {},
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(VictronPalette.SURFACE),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color(VictronPalette.TEXT_DIM),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(VictronPalette.TEXT_DIM),
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = valueColor,
            )
        }
    }
}

@Composable
private fun EmptyState(scanning: Boolean, onOpenDevices: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .clickable(onClick = onOpenDevices),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(if (scanning) R.string.scanning else R.string.empty_no_devices),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Color(VictronPalette.TEXT_DIM),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun BlockedState(
    reason: ScanUnavailable,
    onGrantPermission: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .clickable {
                if (reason == ScanUnavailable.NoPermission) onGrantPermission() else onOpenDevices()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = when (reason) {
                ScanUnavailable.NoPermission -> stringResource(R.string.permission_needed)
                ScanUnavailable.BluetoothOff -> stringResource(R.string.bluetooth_off)
                ScanUnavailable.NoLeSupport -> stringResource(R.string.no_ble)
                is ScanUnavailable.Failed -> "Scan error ${reason.errorCode}"
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (reason == ScanUnavailable.NoPermission) {
            Text(
                text = stringResource(R.string.permission_grant),
                style = MaterialTheme.typography.labelSmall,
                color = Color(VictronPalette.SOLAR),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
