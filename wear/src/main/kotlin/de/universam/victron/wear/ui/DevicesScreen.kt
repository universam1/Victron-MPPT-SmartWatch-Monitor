package de.universam.victron.wear.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import de.universam.victron.data.R as DataR
import de.universam.victron.data.VictronViewModel
import de.universam.victron.data.model.SnapshotStatus
import de.universam.victron.data.update.UpdateState
import de.universam.victron.data.update.isBusy
import de.universam.victron.data.update.updateStatusText
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
                    if (snapshot.displayName != snapshot.modelName) {
                        Text(
                            text = snapshot.modelName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
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
            val keepMinutes = collect(viewModel.keepScreenOnMinutes)
            Button(
                onClick = {
                    val next = when (keepMinutes) {
                        0 -> 2; 2 -> 5; 5 -> 10; else -> 0
                    }
                    viewModel.setKeepScreenOnMinutes(next)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (keepMinutes == 0) {
                        stringResource(R.string.keep_screen_off)
                    } else {
                        stringResource(R.string.keep_screen_on_min, keepMinutes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
        }

        item {
            Button(onClick = { viewModel.syncNow() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_sync), maxLines = 2)
            }
        }

        item {
            Text(
                text = stringResource(R.string.sync_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Button(onClick = onDebugClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_debug))
            }
        }

        // Self update: the watch app is sideloaded, so nothing else will ever refresh it.
        item {
            val context = LocalContext.current
            val updateState = collect(viewModel.updateState)
            Button(
                onClick = { viewModel.updateNow() },
                enabled = !updateState.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = updateStatusText(context, updateState),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (updateState is UpdateState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 3,
                )
            }
        }

        item {
            Button(
                onClick = { viewModel.setAutoUpdateEnabled(!config.autoUpdateEnabled) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (config.autoUpdateEnabled) R.string.auto_update_on else R.string.auto_update_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
        }

        item {
            Text(
                text = stringResource(DataR.string.update_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Text(
                text = "v${viewModel.installedVersionName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
