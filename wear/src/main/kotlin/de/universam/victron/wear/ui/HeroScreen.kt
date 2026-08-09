package de.universam.victron.wear.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
 * The screen you actually look at: a full-bezel power gauge with the watts in the middle and the
 * battery values colour-coded underneath — the same reading order VictronConnect uses.
 *
 * Tap the gauge for all values, tap the name to switch device, tap the bottom pill for settings.
 */
@Composable
fun HeroScreen(
    viewModel: VictronViewModel,
    onOpenDetail: (String) -> Unit,
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(VictronPalette.BACKGROUND)),
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

            else -> Gauge(
                snapshot = selected,
                peakWatts = config.pvPeakWattsFor(selected.address),
                now = now,
                deviceCount = decoded.size,
                onCycleDevice = { index = (index + 1) % decoded.size },
                onOpenDetail = { onOpenDetail(selected.address) },
                onOpenDevices = onOpenDevices,
            )
        }
    }
}

@Composable
private fun Gauge(
    snapshot: DeviceSnapshot,
    peakWatts: Int,
    now: Long,
    deviceCount: Int,
    onCycleDevice: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    val values = snapshot.solarCharger
    val stale = Formatting.isStale(snapshot, now)
    val solar = if (stale) Color(VictronPalette.TEXT_DIM) else Color(VictronPalette.SOLAR)

    PowerArc(
        fraction = snapshot.pvFraction(peakWatts),
        color = solar,
        trackColor = Color(VictronPalette.TRACK),
        modifier = Modifier
            .fillMaxSize()
            .padding(3.dp),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Device name doubles as the device switcher when more than one charger is in range.
        Text(
            text = if (deviceCount > 1) "${snapshot.displayName}  ›" else snapshot.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = Color(VictronPalette.TEXT_DIM),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = deviceCount > 1, onClick = onCycleDevice)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )

        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.clickable(onClick = onOpenDetail),
        ) {
            Text(
                text = values?.pvPowerW?.toString() ?: Formatting.PLACEHOLDER,
                style = MaterialTheme.typography.displayMedium,
                color = solar,
                maxLines = 1,
            )
            Text(
                text = " W",
                style = MaterialTheme.typography.labelMedium,
                color = Color(VictronPalette.TEXT_DIM),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Text(
            text = when {
                values?.hasError == true -> values.chargerErrorLabel ?: stringResource(R.string.label_error)
                else -> values?.chargerStateLabel ?: Formatting.PLACEHOLDER
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (values?.hasError == true) {
                Color(VictronPalette.ERROR)
            } else {
                Color(VictronPalette.TEXT_DIM)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            ValueChip(
                text = Formatting.volts(values?.batteryVoltage),
                color = Color(VictronPalette.BATTERY),
            )
            ValueChip(
                text = Formatting.amps(values?.batteryCurrent),
                color = Color(VictronPalette.currentColor(values?.batteryCurrent)),
            )
        }

        Text(
            text = "${Formatting.energy(values?.yieldTodayWh)} · ${Formatting.age(snapshot, now)}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(VictronPalette.YIELD),
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    // Settings sits on the arc gap at the bottom, out of the reading path.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 2.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color(VictronPalette.SURFACE))
                .clickable(onClick = onOpenDevices),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "⚙", style = MaterialTheme.typography.labelSmall)
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

@Composable
private fun ValueChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(VictronPalette.SURFACE))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}
