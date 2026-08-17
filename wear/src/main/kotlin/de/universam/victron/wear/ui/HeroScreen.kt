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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
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
 *
 * The gauge renders even when no device has been found yet (all values `–`, arcs at zero), so the
 * screen and its navigation can be used — and tested — without a Victron in range.
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

    val decoded = snapshots.filter { it.status == SnapshotStatus.DECODED }
    var index by remember { mutableIntStateOf(0) }
    val selected = decoded.getOrNull(index.coerceIn(0, (decoded.size - 1).coerceAtLeast(0)))

    // Why the gauge has nothing to show — null when there is nothing to explain.
    val blockedBy = (scanState as? ScanState.Unavailable)?.reason
    val status: String? = when (blockedBy) {
        ScanUnavailable.NoPermission -> stringResource(R.string.permission_needed)
        ScanUnavailable.BluetoothOff -> stringResource(R.string.bluetooth_off)
        ScanUnavailable.NoLeSupport -> stringResource(R.string.no_ble)
        is ScanUnavailable.Failed -> "Scan error ${blockedBy.errorCode}"
        null -> when {
            selected != null -> null
            scanState == ScanState.Scanning -> stringResource(R.string.scanning)
            else -> stringResource(R.string.empty_no_devices)
        }
    }

    HeroContent(
        snapshot = selected,
        peakWatts = selected?.let { config.pvPeakWattsFor(it.address) } ?: 0,
        batteryCurrentMax = selected?.let { config.batteryCurrentMaxFor(it.address) }
            ?: config.batteryCurrentMaxFor(""),
        now = now,
        deviceCount = decoded.size,
        status = status,
        onStatusClick = { onOpenDevices() },
        onCycleDevice = { if (decoded.isNotEmpty()) index = (index + 1) % decoded.size },
        onOpenDevices = onOpenDevices,
    )
}

/**
 * The hero surface itself, without any ViewModel: gauge, optional status row, detail rows and the
 * settings button that leads to the rest of the app. Kept stateless so previews render the real UI.
 *
 * A `null` [snapshot] means "nothing decoded yet" — everything still renders, with placeholders.
 */
@Composable
internal fun HeroContent(
    snapshot: DeviceSnapshot?,
    peakWatts: Int,
    batteryCurrentMax: Double,
    now: Long,
    deviceCount: Int = 0,
    status: String? = null,
    onStatusClick: () -> Unit = {},
    onCycleDevice: () -> Unit = {},
    onOpenDevices: () -> Unit = {},
) {
    val values = snapshot?.solarCharger
    // Both defaults assume a list that starts with a ListHeader and would open scrolled onto the
    // *second* item — which here is a detail button, with the gauge above the top of the screen.
    // The gauge is item 0 and it is what the screen is for, so it is both the initial and the
    // anchor item.
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(VictronPalette.BACKGROUND)),
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
            autoCentering = AutoCenteringParams(itemIndex = 0),
        ) {
            // ── First item: fullscreen gauge with arcs ──
            item {
                GaugeFace(
                    snapshot = snapshot,
                    peakWatts = peakWatts,
                    batteryCurrentMax = batteryCurrentMax,
                    now = now,
                    deviceCount = deviceCount,
                    onCycleDevice = onCycleDevice,
                    // A lazy list measures items with an unbounded height, so a plain
                    // `fillMaxSize()` collapses to the content height and the arcs shrink to a
                    // sliver. `fillParentMaxSize()` is the item-scope equivalent that knows the
                    // viewport — and a first item exactly one viewport tall is what makes
                    // auto-centering leave it flush with the top, so it can be scrolled fully
                    // into view.
                    modifier = Modifier.fillParentMaxSize(),
                )
            }

            // ── Why there is nothing to show, and what to do about it ──
            if (status != null) {
                item {
                    Button(
                        onClick = onStatusClick,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(VictronPalette.SURFACE),
                        ),
                    ) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(VictronPalette.TEXT),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ── Detail rows as Wear M3 Buttons ──
            item {
                BatteryDetailButton(
                    voltage = Formatting.volts(values?.batteryVoltage),
                    current = Formatting.amps(values?.batteryCurrent),
                    power = Formatting.watts(values?.batteryPowerW),
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
                    value = snapshot?.let { "${it.rssi} dBm" } ?: Formatting.PLACEHOLDER,
                    valueColor = Color(VictronPalette.TEXT_DIM),
                )
            }
            item {
                DetailButton(
                    icon = Icons.Filled.Info,
                    label = stringResource(R.string.label_model),
                    value = snapshot?.modelName ?: Formatting.PLACEHOLDER,
                    valueColor = Color(VictronPalette.TEXT_DIM),
                )
            }
            item {
                DetailButton(
                    icon = Icons.Filled.Schedule,
                    label = stringResource(R.string.label_age),
                    value = snapshot?.let { Formatting.age(it, now) } ?: Formatting.PLACEHOLDER,
                    valueColor = Color(VictronPalette.TEXT_DIM),
                )
            }

            // ── The one way out of this screen: device list, keys, settings, raw data ──
            item {
                Button(
                    onClick = onOpenDevices,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.devices_title),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** The gauge face — arcs + large watts/amps. Extracted so previews reuse the real UI. */
@Composable
internal fun GaugeFace(
    snapshot: DeviceSnapshot?,
    peakWatts: Int,
    batteryCurrentMax: Double,
    now: Long,
    deviceCount: Int = 1,
    onCycleDevice: () -> Unit = {},
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val values = snapshot?.solarCharger
    val stale = snapshot != null && Formatting.isStale(snapshot, now)
    val solar = if (stale) Color(VictronPalette.TEXT_DIM) else Color(VictronPalette.SOLAR)
    val currentColor = if (stale) {
        Color(VictronPalette.TEXT_DIM)
    } else {
        Color(VictronPalette.currentColor(values?.batteryCurrent))
    }
    Box(
        modifier = modifier.background(Color(VictronPalette.BACKGROUND)),
        contentAlignment = Alignment.Center,
    ) {
        PowerArc(
            fraction = snapshot?.pvFraction(peakWatts) ?: 0f,
            color = solar,
            trackColor = Color(VictronPalette.TRACK),
            modifier = Modifier.fillMaxSize(),
            gradientColors = if (stale) null else listOf(
                Color(VictronPalette.HEAT_LOW),
                Color(VictronPalette.HEAT_MID),
                Color(VictronPalette.HEAT_HIGH),
            ),
        )
        PowerArc(
            fraction = snapshot?.batteryCurrentFraction(batteryCurrentMax) ?: 0f,
            color = currentColor,
            trackColor = Color(VictronPalette.TRACK),
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 11.dp,
            startAngle = 38f,
            sweepAngle = 104f,
            gradientColors = if (stale) null else listOf(
                Color(VictronPalette.CURRENT_LOW),
                Color(VictronPalette.CURRENT_MID),
                Color(VictronPalette.CURRENT_HIGH),
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = when {
                    snapshot == null -> stringResource(R.string.overview_title)
                    deviceCount > 1 -> "${snapshot.displayName}  ›"
                    else -> snapshot.displayName
                },
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
                text = snapshot?.let { Formatting.age(it, now) } ?: Formatting.PLACEHOLDER,
                style = MaterialTheme.typography.labelSmall,
                color = Color(VictronPalette.TEXT_DIM),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** A Wear M3 Button styled as a detail row: icon + label on the left, value on the right. */
@Composable
internal fun DetailButton(icon: ImageVector, label: String, value: String, valueColor: Color) {
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

/** Battery detail: icon + label on top, three values right-aligned below. */
@Composable
internal fun BatteryDetailButton(voltage: String, current: String, power: String) {
    Button(
        onClick = {},
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(VictronPalette.SURFACE),
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.BatteryChargingFull,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color(VictronPalette.TEXT_DIM),
                )
                Text(
                    text = stringResource(R.string.label_battery),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(VictronPalette.TEXT_DIM),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(text = voltage, style = MaterialTheme.typography.titleSmall, color = Color(VictronPalette.BATTERY))
                Text(text = current, style = MaterialTheme.typography.titleSmall, color = Color(VictronPalette.BATTERY))
                Text(text = power, style = MaterialTheme.typography.titleSmall, color = Color(VictronPalette.BATTERY))
            }
        }
    }
}
