package de.universam.victron.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import de.universam.victron.data.VictronViewModel
import de.universam.victron.wear.R

private const val KEY_LENGTH = 32

/**
 * Entering a 32 character hex key on a watch.
 *
 * A hex keypad instead of the system keyboard: it is one-time work, but it must not fail. A
 * 4×4 grid of hex digits cannot mistype a letter that is not hex, and it works the same on a
 * round display, with gloves, without voice input.
 */
@Composable
fun KeyEntryScreen(
    viewModel: VictronViewModel,
    address: String,
    onDone: () -> Unit,
) {
    var key by remember { mutableStateOf(viewModel.keyFor(address).orEmpty()) }
    var error by remember { mutableStateOf(false) }

    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 28.dp),
    ) {
        item { ListHeader { Text(stringResource(R.string.key_entry_title)) } }

        item {
            Text(
                text = formatKey(key),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Text(
                text = if (error) {
                    stringResource(R.string.key_entry_invalid)
                } else {
                    "${key.length} / $KEY_LENGTH · $address"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            HexKeypad(
                onDigit = { digit ->
                    if (key.length < KEY_LENGTH) {
                        key += digit
                        error = false
                    }
                },
                onBackspace = { key = key.dropLast(1) },
            )
        }

        item {
            Button(
                onClick = {
                    if (viewModel.saveKey(address, key)) {
                        onDone()
                    } else {
                        error = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }

        item {
            Button(
                onClick = {
                    viewModel.removeDevice(address)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_remove))
            }
        }

        item {
            Text(
                text = stringResource(R.string.key_entry_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HexKeypad(onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf("0123", "4567", "89ab", "cdef")
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
            ) {
                row.forEach { digit -> HexKey(digit.uppercase()) { onDigit(digit) } }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            HexKey("⌫", onClick = onBackspace)
        }
    }
}

/**
 * A keypad key. Deliberately a plain clickable box: it has to be exactly this small, which the
 * standard buttons refuse to be.
 */
@Composable
private fun HexKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

/** `adec cb94 …` — chunked so a 32 character key can be checked against the phone screen. */
private fun formatKey(key: String): String =
    if (key.isEmpty()) "–" else key.chunked(4).joinToString(" ")
