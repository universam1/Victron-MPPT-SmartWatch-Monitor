package de.universam.victron.wear.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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

/**
 * What the radio actually delivered. This is the screen that makes adding a new record type
 * possible: it shows the decrypted payload of records this build cannot decode yet.
 */
@Composable
fun DebugScreen(viewModel: VictronViewModel) {
    ScanWhileVisible(viewModel)

    val snapshots = collect(viewModel.snapshots)
    val now = rememberNow()

    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 30.dp),
    ) {
        item { ListHeader { Text(stringResource(R.string.debug_title)) } }

        snapshots.forEach { snapshot ->
            item {
                Text(
                    text = "${snapshot.address}  ${snapshot.rssi} dBm  ${Formatting.age(snapshot, now)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    text = "0x${snapshot.modelId.toString(16).uppercase()} · " +
                        "${snapshot.recordLabel} (0x${snapshot.recordTypeCode.toString(16).uppercase()}) · " +
                        snapshot.status.name,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            snapshot.payloadHex?.let { hex ->
                item {
                    Text(
                        text = hex,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
