package de.universam.victron.wear.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import de.universam.victron.data.VictronViewModel
import kotlinx.coroutines.delay

internal object Route {
    const val OVERVIEW = "overview"
    const val DEVICES = "devices"
    const val DEBUG = "debug"
    const val DETAIL = "detail/{address}"
    const val KEY_ENTRY = "key/{address}"

    fun detail(address: String): String = "detail/$address"
    fun keyEntry(address: String): String = "key/$address"
}

internal const val BLUETOOTH_SCAN_PERMISSION: String = Manifest.permission.BLUETOOTH_SCAN

@Composable
fun VictronApp(viewModel: VictronViewModel = viewModel()) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val navController: NavHostController = rememberSwipeDismissableNavController()

            SwipeDismissableNavHost(
                navController = navController,
                startDestination = Route.OVERVIEW,
            ) {
                composable(Route.OVERVIEW) {
                    OverviewScreen(
                        viewModel = viewModel,
                        onDeviceClick = { address -> navController.navigate(Route.detail(address)) },
                        onNeedsKey = { address -> navController.navigate(Route.keyEntry(address)) },
                        onSettingsClick = { navController.navigate(Route.DEVICES) },
                    )
                }
                composable(Route.DEVICES) {
                    DevicesScreen(
                        viewModel = viewModel,
                        onEditKey = { address -> navController.navigate(Route.keyEntry(address)) },
                        onDebugClick = { navController.navigate(Route.DEBUG) },
                    )
                }
                composable(Route.DEBUG) {
                    DebugScreen(viewModel = viewModel)
                }
                composable(Route.DETAIL) { entry ->
                    DetailScreen(
                        viewModel = viewModel,
                        address = entry.arguments?.getString("address").orEmpty(),
                    )
                }
                composable(Route.KEY_ENTRY) { entry ->
                    KeyEntryScreen(
                        viewModel = viewModel,
                        address = entry.arguments?.getString("address").orEmpty(),
                        onDone = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/** Scans while the composable is on screen, and stops as soon as it leaves. */
@Composable
internal fun ScanWhileVisible(viewModel: VictronViewModel) {
    DisposableEffect(Unit) {
        viewModel.startLiveScan()
        onDispose { viewModel.stopLiveScan() }
    }
}

/** A clock that ticks once per second, so "42s ago" actually counts up. */
@Composable
internal fun rememberNow(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Convenience so screens can read state flows without repeating the import. */
@Composable
internal fun <T> collect(flow: kotlinx.coroutines.flow.StateFlow<T>): T =
    flow.collectAsStateWithLifecycle().value
