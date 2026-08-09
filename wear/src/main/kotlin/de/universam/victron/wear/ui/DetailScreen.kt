package de.universam.victron.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import de.universam.victron.data.Formatting
import de.universam.victron.data.VictronViewModel
import de.universam.victron.wear.R

/** All values of one device, one row per measurement. */
@Composable
fun DetailScreen(viewModel: VictronViewModel, address: String) {
    ScanWhileVisible(viewModel)

    val snapshots = collect(viewModel.snapshots)
    val now = rememberNow()
    val snapshot = snapshots.firstOrNull { it.address.equals(address, ignoreCase = true) }

    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 30.dp),
    ) {
        item { ListHeader { Text(snapshot?.displayName ?: address) } }

        if (snapshot == null) {
            item { Text(stringResource(R.string.scanning), style = MaterialTheme.typography.bodySmall) }
            return@ScalingLazyColumn
        }

        val values = snapshot.solarCharger
        if (values != null) {
            item { ValueRow(stringResource(R.string.label_pv), Formatting.watts(values.pvPowerW)) }
            item { ValueRow(stringResource(R.string.label_battery), Formatting.volts(values.batteryVoltage)) }
            item { ValueRow("", Formatting.amps(values.batteryCurrent)) }
            item { ValueRow("", Formatting.watts(values.batteryPowerW)) }
            item { ValueRow(stringResource(R.string.label_yield_today), Formatting.energy(values.yieldTodayWh)) }
            item { ValueRow(stringResource(R.string.label_state), values.chargerStateLabel ?: Formatting.PLACEHOLDER) }
            if (values.hasError) {
                item {
                    ValueRow(
                        stringResource(R.string.label_error),
                        values.chargerErrorLabel ?: "Err ${values.chargerErrorCode}",
                    )
                }
            }
            values.loadCurrent?.let { load ->
                item { ValueRow(stringResource(R.string.label_load), Formatting.amps(load)) }
            }
        } else {
            item { ValueRow(stringResource(R.string.label_record), snapshot.recordLabel) }
            snapshot.payloadHex?.let { hex ->
                item { Text(hex, style = MaterialTheme.typography.bodySmall) }
            }
        }

        item { ValueRow(stringResource(R.string.label_age), Formatting.age(snapshot, now)) }
        item { ValueRow(stringResource(R.string.label_signal), "${snapshot.rssi} dBm") }
        item { ValueRow(stringResource(R.string.label_model), snapshot.modelName) }
        item { ValueRow(stringResource(R.string.label_address), snapshot.address) }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
