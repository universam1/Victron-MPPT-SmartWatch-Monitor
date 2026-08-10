package de.universam.victron.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.universam.victron.data.Formatting
import de.universam.victron.data.ScanState
import de.universam.victron.data.ScanUnavailable
import de.universam.victron.data.VictronViewModel
import de.universam.victron.data.model.AppConfig
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.ReadingHistory
import de.universam.victron.data.model.SnapshotStatus
import de.universam.victron.mobile.R
import de.universam.victron.mobile.ui.dashboard.DashboardScreen
import kotlinx.coroutines.delay

private const val BLUETOOTH_SCAN = android.Manifest.permission.BLUETOOTH_SCAN

private sealed interface Screen {
    data object Dashboard : Screen
    data object Setup : Screen
}

/**
 * Phone side of the same app: identical data, identical scanning, but with room for a text field
 * so pasting a 32 character key is not a chore. Auto-navigates to a fullscreen dashboard when
 * decoded data is available.
 */
@Composable
fun MobileApp(viewModel: VictronViewModel = viewModel()) {
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.startLiveScan()
        onDispose { viewModel.stopLiveScan() }
    }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    // Auto-navigate to dashboard when decoded devices exist (fires once).
    var screen by remember { mutableStateOf<Screen>(Screen.Setup) }
    var hasAutoNavigated by remember { mutableStateOf(false) }
    val hasDecoded = snapshots.any { it.status == SnapshotStatus.DECODED }

    LaunchedEffect(hasDecoded) {
        if (hasDecoded && !hasAutoNavigated) {
            screen = Screen.Dashboard
            hasAutoNavigated = true
        }
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { target ->
        when (target) {
            Screen.Dashboard -> DashboardScreen(
                snapshots = snapshots,
                config = config,
                now = now,
                history = history,
                onOpenSetup = { screen = Screen.Setup },
            )

            Screen.Setup -> SetupContent(
                viewModel = viewModel,
                snapshots = snapshots,
                config = config,
                scanState = scanState,
                now = now,
                onOpenDashboard = if (hasDecoded) {
                    { screen = Screen.Dashboard }
                } else null,
            )
        }
    }
}

@Composable
private fun SetupContent(
    viewModel: VictronViewModel,
    snapshots: List<DeviceSnapshot>,
    config: AppConfig,
    scanState: ScanState,
    now: Long,
    onOpenDashboard: (() -> Unit)?,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.retryScan() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.devices),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (scanState == ScanState.Scanning) {
                        Text(
                            text = stringResource(R.string.scanning),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (onOpenDashboard != null) {
                        OutlinedButton(onClick = onOpenDashboard) {
                            Text(stringResource(R.string.dashboard_view))
                        }
                    }
                }
            }
        }

        (scanState as? ScanState.Unavailable)?.let { unavailable ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = when (val reason = unavailable.reason) {
                                ScanUnavailable.NoPermission -> stringResource(R.string.permission_needed)
                                ScanUnavailable.BluetoothOff -> stringResource(R.string.bluetooth_off)
                                ScanUnavailable.NoLeSupport -> stringResource(R.string.no_ble)
                                is ScanUnavailable.Failed -> "Scan error ${reason.errorCode}"
                            },
                        )
                        if (unavailable.reason == ScanUnavailable.NoPermission) {
                            Button(onClick = { permissionLauncher.launch(BLUETOOTH_SCAN) }) {
                                Text(stringResource(R.string.permission_grant))
                            }
                        }
                    }
                }
            }
        }

        if (snapshots.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(snapshots, key = { it.address }) { snapshot ->
            DeviceCard(
                snapshot = snapshot,
                now = now,
                existingKey = config.keyFor(snapshot.address),
                pvPeakWatts = config.pvPeakWattsFor(snapshot.address),
                batteryCurrentMax = config.batteryCurrentMaxFor(snapshot.address),
                onSaveKey = { key -> viewModel.saveKey(snapshot.address, key) },
                onSavePeak = { watts -> viewModel.setPvPeakWatts(snapshot.address, watts) },
                onSaveBatteryMax = { amps -> viewModel.setBatteryCurrentMax(snapshot.address, amps) },
                onRemove = { viewModel.removeDevice(snapshot.address) },
            )
        }

        item { HorizontalDivider() }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.sync_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.sync_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { viewModel.syncNow() }) {
                    Text(stringResource(R.string.sync_now))
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.background_scan))
                Switch(
                    checked = config.backgroundScanEnabled,
                    onCheckedChange = { viewModel.setBackgroundScanEnabled(it) },
                )
            }
        }

        item {
            OutlinedButton(onClick = { viewModel.requestScanNow() }) {
                Text(stringResource(R.string.scan_now))
            }
        }
    }
}

@Composable
private fun DeviceCard(
    snapshot: DeviceSnapshot,
    now: Long,
    existingKey: String?,
    pvPeakWatts: Int,
    batteryCurrentMax: Double,
    onSaveKey: (String) -> Boolean,
    onSavePeak: (Int) -> Unit,
    onSaveBatteryMax: (Double) -> Unit,
    onRemove: () -> Unit,
) {
    var keyInput by remember(snapshot.address, existingKey) { mutableStateOf(existingKey.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    var peakInput by remember(snapshot.address, pvPeakWatts) {
        mutableStateOf(if (pvPeakWatts > 0) pvPeakWatts.toString() else "")
    }
    var batteryMaxInput by remember(snapshot.address, batteryCurrentMax) {
        mutableStateOf(batteryCurrentMax.toInt().toString())
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = snapshot.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${snapshot.address} · ${snapshot.rssi} dBm · ${Formatting.age(snapshot, now)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (snapshot.status) {
                SnapshotStatus.DECODED -> {
                    val values = snapshot.solarCharger
                    ValueRow(stringResource(R.string.label_pv), Formatting.watts(values?.pvPowerW))
                    ValueRow(
                        stringResource(R.string.label_battery),
                        "${Formatting.volts(values?.batteryVoltage)}  ${Formatting.amps(values?.batteryCurrent)}",
                    )
                    ValueRow(stringResource(R.string.label_yield_today), Formatting.energy(values?.yieldTodayWh))
                    ValueRow(
                        stringResource(R.string.label_state),
                        values?.chargerStateLabel ?: Formatting.PLACEHOLDER,
                    )
                    if (values?.hasError == true) {
                        ValueRow(
                            stringResource(R.string.label_error),
                            values.chargerErrorLabel ?: "Err ${values.chargerErrorCode}",
                        )
                    }
                    values?.loadCurrent?.let {
                        ValueRow(stringResource(R.string.label_load), Formatting.amps(it))
                    }
                }

                SnapshotStatus.MISSING_KEY -> Text(stringResource(R.string.status_missing_key))
                SnapshotStatus.KEY_MISMATCH -> Text(
                    text = stringResource(R.string.status_key_mismatch),
                    color = MaterialTheme.colorScheme.error,
                )

                SnapshotStatus.UNDECODED_RECORD -> {
                    Text("${snapshot.recordLabel}: ${stringResource(R.string.status_undecoded)}")
                    snapshot.payloadHex?.let { hex ->
                        Text(
                            text = "${stringResource(R.string.label_payload)}: $hex",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = keyInput,
                onValueChange = {
                    keyInput = it.trim()
                    invalid = false
                },
                label = { Text(stringResource(R.string.key_label)) },
                isError = invalid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (invalid) {
                Text(
                    text = stringResource(R.string.key_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = stringResource(R.string.key_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { invalid = !onSaveKey(keyInput) }) {
                    Text(stringResource(R.string.key_save))
                }
                if (existingKey != null) {
                    OutlinedButton(onClick = onRemove) {
                        Text(stringResource(R.string.key_remove))
                    }
                }
            }

            if (existingKey != null) {
                // Full scale of the gauge on the watch. Empty means "use the highest power seen".
                OutlinedTextField(
                    value = peakInput,
                    onValueChange = { input ->
                        peakInput = input.filter { it.isDigit() }.take(5)
                        onSavePeak(peakInput.toIntOrNull() ?: 0)
                    },
                    label = { Text(stringResource(R.string.pv_peak_label)) },
                    supportingText = { Text(stringResource(R.string.pv_peak_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Full scale of the battery current arc. Empty means default 15 A.
                OutlinedTextField(
                    value = batteryMaxInput,
                    onValueChange = { input ->
                        batteryMaxInput = input.filter { it.isDigit() }.take(3)
                        onSaveBatteryMax((batteryMaxInput.toIntOrNull() ?: 15).toDouble())
                    },
                    label = { Text(stringResource(R.string.battery_current_max_label)) },
                    supportingText = { Text(stringResource(R.string.battery_current_max_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}
