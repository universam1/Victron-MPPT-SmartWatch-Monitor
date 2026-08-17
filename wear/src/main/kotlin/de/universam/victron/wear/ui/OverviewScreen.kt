package de.universam.victron.wear.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import de.universam.victron.data.Formatting
import de.universam.victron.data.ScanState
import de.universam.victron.data.ScanUnavailable
import de.universam.victron.data.VictronViewModel
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.SnapshotStatus
import de.universam.victron.wear.R

/**
 * The main screen: one card per device. Scans only while it is visible — that is the deal we make
 * with the watch battery.
 */
@Composable
fun OverviewScreen(
    viewModel: VictronViewModel,
    onDeviceClick: (String) -> Unit,
    onNeedsKey: (String) -> Unit,
    onSettingsClick: () -> Unit,
) {
    ScanWhileVisible(viewModel)

    val snapshots = collect(viewModel.snapshots)
    val scanState = collect(viewModel.scanState)
    val now = rememberNow()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.retryScan() }

    // Auto-request BLE permission on startup (once per launch).
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(scanState) {
        if (!permissionRequested &&
            (scanState as? ScanState.Unavailable)?.reason == ScanUnavailable.NoPermission
        ) {
            permissionRequested = true
            permissionLauncher.launch(BLUETOOTH_SCAN_PERMISSION)
        }
    }

    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 30.dp),
    ) {
        item {
            ListHeader {
                Text(
                    text = when (scanState) {
                        ScanState.Scanning -> stringResource(R.string.scanning)
                        else -> stringResource(R.string.overview_title)
                    },
                )
            }
        }

        if (scanState is ScanState.Unavailable &&
            scanState.reason != ScanUnavailable.NoPermission
        ) {
            item {
                UnavailableCard(reason = scanState.reason)
            }
        }

        if (snapshots.isEmpty()) {
            item {
                Card(onClick = onSettingsClick, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.empty_no_devices),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        snapshots.forEach { snapshot ->
            item {
                DeviceCard(
                    snapshot = snapshot,
                    now = now,
                    onClick = {
                        if (snapshot.status == SnapshotStatus.MISSING_KEY ||
                            snapshot.status == SnapshotStatus.KEY_MISMATCH
                        ) {
                            onNeedsKey(snapshot.address)
                        } else {
                            onDeviceClick(snapshot.address)
                        }
                    },
                )
            }
        }

        item {
            Button(onClick = onSettingsClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_settings))
            }
        }
    }
}

@Composable
private fun DeviceCard(snapshot: DeviceSnapshot, now: Long, onClick: () -> Unit) {
    val stale = Formatting.isStale(snapshot, now)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = snapshot.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            when (snapshot.status) {
                SnapshotStatus.DECODED -> {
                    val values = snapshot.solarCharger
                    val fallbackErrorLabel = stringResource(R.string.label_error)
                    Text(
                        text = Formatting.watts(values?.pvPowerW),
                        style = MaterialTheme.typography.displaySmall,
                        color = if (stale) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    Text(
                        text = "${Formatting.volts(values?.batteryVoltage)}  " +
                            Formatting.amps(values?.batteryCurrent),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = buildString {
                            append(
                                if (values?.hasError == true) {
                                    values.chargerErrorLabel ?: fallbackErrorLabel
                                } else {
                                    values?.chargerStateLabel ?: Formatting.PLACEHOLDER
                                },
                            )
                            append(" · ")
                            append(Formatting.age(snapshot, now))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (values?.hasError == true) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                SnapshotStatus.MISSING_KEY -> StatusLine(stringResource(R.string.status_missing_key))
                SnapshotStatus.KEY_MISMATCH -> StatusLine(stringResource(R.string.status_key_mismatch))
                SnapshotStatus.UNDECODED_RECORD -> StatusLine(
                    "${snapshot.recordLabel}: ${stringResource(R.string.status_undecoded)}",
                )
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun UnavailableCard(reason: ScanUnavailable) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
    ) {
        val message = when (reason) {
            ScanUnavailable.BluetoothOff -> stringResource(R.string.bluetooth_off)
            ScanUnavailable.NoLeSupport -> stringResource(R.string.no_ble)
            is ScanUnavailable.Failed -> "Scan error ${reason.errorCode}"
            else -> ""
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
