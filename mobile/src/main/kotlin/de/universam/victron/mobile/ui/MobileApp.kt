package de.universam.victron.mobile.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.universam.victron.data.Formatting
import de.universam.victron.data.R as DataR
import de.universam.victron.data.ScanState
import de.universam.victron.data.ScanUnavailable
import de.universam.victron.data.SyncResult
import de.universam.victron.data.VictronViewModel
import de.universam.victron.data.model.AppConfig
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.ReadingHistory
import de.universam.victron.data.model.SnapshotStatus
import de.universam.victron.data.update.UpdateState
import de.universam.victron.data.update.isBusy
import de.universam.victron.data.update.updateStatusText
import de.universam.victron.mobile.R
import de.universam.victron.mobile.ui.dashboard.DashboardScreen
import kotlinx.coroutines.delay

/** The runtime permission that gates BLE scanning on this API level. */
private val BLE_PERMISSION = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
    android.Manifest.permission.BLUETOOTH_SCAN
} else {
    android.Manifest.permission.ACCESS_FINE_LOCATION
}

/** Widest the setup form gets — beyond this a text field is just a long line to read. */
private val MAX_FORM_WIDTH = 560.dp

/** Enum rather than a sealed hierarchy so `rememberSaveable` can carry it through a rotation. */
private enum class Screen { Dashboard, Setup }

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
            permissionLauncher.launch(BLE_PERMISSION)
        }
    }

    DisposableEffect(Unit) {
        viewModel.startLiveScan()
        onDispose { viewModel.stopLiveScan() }
    }

    // Keep the screen on for the configured duration, then release.
    val keepMinutes by viewModel.keepScreenOnMinutes.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    LaunchedEffect(keepMinutes) {
        val window = activity?.window ?: return@LaunchedEffect
        if (keepMinutes > 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            delay(keepMinutes * 60_000L)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    // Auto-navigate to dashboard when decoded devices exist (fires once). Saved, not just
    // remembered: a rotation recreates the Activity and must not throw the user back to setup.
    var screen by rememberSaveable { mutableStateOf(Screen.Setup) }
    var hasAutoNavigated by rememberSaveable { mutableStateOf(false) }
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
                // Reachable with no device too: the dashboard then shows placeholders, which is
                // enough to check the layout and the navigation without a Victron in range.
                onOpenDashboard = { screen = Screen.Dashboard },
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
    onOpenDashboard: () -> Unit,
) {
    // A form stretched across a landscape phone is unreadable, so the content keeps a sane
    // measure and centres in whatever width is left.
    Box(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.widthIn(max = MAX_FORM_WIDTH).fillMaxSize(),
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
                        OutlinedButton(onClick = onOpenDashboard) {
                            Text(stringResource(R.string.dashboard_view))
                        }
                    }
                }
            }

            (scanState as? ScanState.Unavailable)?.let { unavailable ->
                if (unavailable.reason != ScanUnavailable.NoPermission) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = when (val reason = unavailable.reason) {
                                        ScanUnavailable.BluetoothOff -> stringResource(R.string.bluetooth_off)
                                        ScanUnavailable.NoLeSupport -> stringResource(R.string.no_ble)
                                        is ScanUnavailable.Failed -> "Scan error ${reason.errorCode}"
                                        else -> ""
                                    },
                                )
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
                    onSaveKey = { key -> viewModel.saveKey(snapshot.address, key) },
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
                    val syncState = viewModel.syncState.collectAsStateWithLifecycle().value
                    OutlinedButton(
                        onClick = { viewModel.syncNow() },
                        enabled = syncState !is SyncResult.Syncing,
                    ) {
                        Text(
                            text = when (syncState) {
                                is SyncResult.Syncing -> stringResource(R.string.sync_syncing)
                                is SyncResult.Done -> stringResource(R.string.sync_done, syncState.deviceCount)
                                is SyncResult.Failed -> stringResource(R.string.sync_failed)
                                else -> stringResource(R.string.sync_now)
                            },
                            color = when (syncState) {
                                is SyncResult.Done -> MaterialTheme.colorScheme.primary
                                is SyncResult.Failed -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.keep_screen_on))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val options = listOf(0, 5, 10, 30, 60)
                        options.forEach { minutes ->
                            FilterChip(
                                selected = config.keepScreenOnMinutes == minutes,
                                onClick = { viewModel.setKeepScreenOnMinutes(minutes) },
                                label = {
                                    Text(
                                        if (minutes == 0) stringResource(R.string.keep_screen_on_off)
                                        else stringResource(R.string.keep_screen_on_minutes, minutes),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            item {
                OutlinedButton(onClick = { viewModel.requestScanNow() }) {
                    Text(stringResource(R.string.scan_now))
                }
            }

            item { HorizontalDivider() }

            item { UpdateSection(viewModel = viewModel, config = config) }
        }
    }
}

@Composable
private fun DeviceCard(
    snapshot: DeviceSnapshot,
    now: Long,
    existingKey: String?,
    onSaveKey: (String) -> Boolean,
    onRemove: () -> Unit,
) {
    var keyInput by rememberSaveable(snapshot.address, existingKey) {
        mutableStateOf(existingKey.orEmpty())
    }
    var invalid by rememberSaveable { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = snapshot.displayName, style = MaterialTheme.typography.titleMedium)
            if (snapshot.displayName != snapshot.modelName) {
                Text(
                    text = snapshot.modelName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        }
    }
}

/**
 * Self update, because the app ships outside any store.
 *
 * The switch drives the background check; the button is the manual path and shows every step, so
 * a failed download is visible instead of silently leaving the device on an old build. The labels
 * come from `:data` so the watch says the same things.
 */
@Composable
private fun UpdateSection(viewModel: VictronViewModel, config: AppConfig) {
    val context = LocalContext.current
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(DataR.string.update_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(DataR.string.update_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(DataR.string.update_auto))
            Switch(
                checked = config.autoUpdateEnabled,
                onCheckedChange = { viewModel.setAutoUpdateEnabled(it) },
            )
        }
        OutlinedButton(
            onClick = { viewModel.updateNow() },
            enabled = !updateState.isBusy,
        ) {
            Text(
                text = updateStatusText(context, updateState),
                color = when (updateState) {
                    is UpdateState.Failed -> MaterialTheme.colorScheme.error
                    is UpdateState.Ready -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Text(
            text = "v${viewModel.installedVersionName}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
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
