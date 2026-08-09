package de.universam.victron.wear.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import de.universam.victron.data.VictronViewModel
import de.universam.victron.data.model.SnapshotStatus
import de.universam.victron.wear.R

/**
 * Device list and settings.
 *
 * Discovery works without any key: model id, record type and address travel in the clear, so
 * every Victron device in range shows up here and can be given its key.
 */
@Composable
fun DevicesScreen(
    viewModel: VictronViewModel,
    onEditKey: (String) -> Unit,
    onDebugClick: () -> Unit,
) {
    ScanWhileVisible(viewModel)

    val snapshots = collect(viewModel.snapshots)
    val config = collect(viewModel.config)

    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 30.dp),
    ) {
        item { ListHeader { Text(stringResource(R.string.devices_title)) } }

        snapshots.forEach { snapshot ->
            item {
                val hasKey = config.keyFor(snapshot.address) != null
                Card(onClick = { onEditKey(snapshot.address) }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = snapshot.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        text = snapshot.address,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = when {
                            snapshot.status == SnapshotStatus.DECODED -> "${snapshot.recordLabel} · ${snapshot.rssi} dBm"
                            snapshot.status == SnapshotStatus.KEY_MISMATCH ->
                                stringResource(R.string.status_key_mismatch)

                            hasKey -> stringResource(R.string.status_undecoded)
                            else -> stringResource(R.string.status_missing_key)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (snapshots.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { ListHeader { Text(stringResource(R.string.settings_title)) } }

        item {
            Button(
                onClick = { viewModel.setBackgroundScanEnabled(!config.backgroundScanEnabled) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (config.backgroundScanEnabled) {
                        stringResource(R.string.background_scan_on)
                    } else {
                        stringResource(R.string.background_scan_off)
                    },
                    maxLines = 2,
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.background_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Button(onClick = { viewModel.requestScanNow() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_rescan))
            }
        }

        item {
            Button(onClick = onDebugClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_debug))
            }
        }
    }
}
