package de.universam.victron.wear.tile

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import de.universam.victron.data.Formatting
import de.universam.victron.data.ScanAggressiveness
import de.universam.victron.data.ScanScheduler
import de.universam.victron.data.VictronData
import de.universam.victron.data.VictronPalette
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.SnapshotStatus
import de.universam.victron.wear.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The tile: the same power gauge as the app — arc along the bezel, watts in the middle, battery
 * values colour-coded — and nothing that needs scrolling.
 *
 * It never scans by itself. A tile service must return quickly and cannot start long-running work,
 * so the tile renders the cached snapshot and asks [ScanScheduler] for a short scan window when the
 * user actually looks at it. That scan pushes an update when it is done.
 */
class VictronTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        // The user is looking at the tile right now: worth a short, aggressive scan.
        ScanScheduler.requestScanNow(this, seconds = ENTER_SCAN_SECONDS, aggressiveness = ScanAggressiveness.LowLatency)
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = future("tile") {
        val repository = VictronData.repository(applicationContext)
        repository.loadCachedSnapshots()
        val config = repository.config.first()
        val now = System.currentTimeMillis()

        val snapshot = repository.snapshots.value.values
            .filter { config.tileAddresses.isEmpty() || config.tileAddresses.any { a -> a.equals(it.address, true) } }
            .filter { it.status == SnapshotStatus.DECODED }
            .maxByOrNull { it.receivedAtEpochMillis }

        if (snapshot == null || Formatting.isStale(snapshot, now)) {
            ScanScheduler.requestScanNow(applicationContext, seconds = ENTER_SCAN_SECONDS)
        }

        val peakWatts = snapshot?.let { config.pvPeakWattsFor(it.address) } ?: 0

        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MILLIS)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(
                    TileLayout.build(applicationContext, snapshot, peakWatts, now),
                ),
            )
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = future("resources") {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    /** Bridges a suspending builder to the ListenableFuture API the tile framework expects. */
    private fun <T> future(tag: String, block: suspend () -> T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            val job = scope.launch {
                runCatching { block() }
                    .onSuccess { completer.set(it) }
                    .onFailure { completer.setException(it) }
            }
            completer.addCancellationListener({ job.cancel() }, { runnable -> runnable.run() })
            tag
        }

    companion object {
        private const val RESOURCES_VERSION = "1"

        /** How long the renderer may keep this tile before asking again. */
        private const val FRESHNESS_MILLIS = 60_000L

        private const val ENTER_SCAN_SECONDS = 12

        /** Ask the platform to re-request the tile, e.g. after a background scan. */
        fun requestUpdate(context: Context) {
            TileService.getUpdater(context).requestUpdate(VictronTileService::class.java)
        }
    }
}

/**
 * Layout of the tile, built with the low level ProtoLayout builders.
 *
 * The gauge is two overlaid [LayoutElementBuilders.Arc]s (track + value) rather than a material
 * progress indicator: core ProtoLayout elements, no extra dependency, and full control over the
 * 240° sweep so the gap sits at the bottom exactly like in the app.
 */
private object TileLayout {

    private const val ARC_ANCHOR = -120f
    private const val ARC_SWEEP = 240f
    private const val ARC_THICKNESS_DP = 8f

    fun build(
        context: Context,
        snapshot: DeviceSnapshot?,
        peakWatts: Int,
        now: Long,
    ): LayoutElementBuilders.LayoutElement {
        val stale = snapshot != null && Formatting.isStale(snapshot, now)
        val solarColor = if (stale) VictronPalette.TEXT_DIM else VictronPalette.SOLAR

        val content = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(clickableToApp(context))
            .addContent(arc(ARC_SWEEP, VictronPalette.TRACK))

        if (snapshot != null) {
            val fraction = snapshot.pvFraction(peakWatts)
            if (fraction > 0f) {
                content.addContent(arc(ARC_SWEEP * fraction, solarColor))
            }
        }

        content.addContent(center(snapshot, solarColor, now))
        return content.build()
    }

    private fun center(
        snapshot: DeviceSnapshot?,
        solarColor: Int,
        now: Long,
    ): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(wrap())
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        if (snapshot == null) {
            return column
                .addContent(text("Victron", 13f, VictronPalette.TEXT_DIM))
                .addContent(spacer(4f))
                .addContent(text("No data", 22f, VictronPalette.TEXT, bold = true))
                .addContent(spacer(4f))
                .addContent(text("Tap to set up", 12f, VictronPalette.TEXT_DIM))
                .build()
        }

        val values = snapshot.solarCharger
        val state = when {
            values?.hasError == true -> values.chargerErrorLabel ?: "Error"
            else -> values?.chargerStateLabel ?: Formatting.PLACEHOLDER
        }
        val stateColor = if (values?.hasError == true) VictronPalette.ERROR else VictronPalette.TEXT_DIM

        return column
            .addContent(text(snapshot.displayName, 12f, VictronPalette.TEXT_DIM, maxLines = 1))
            .addContent(spacer(1f))
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)
                    .addContent(
                        text(values?.pvPowerW?.toString() ?: Formatting.PLACEHOLDER, 30f, solarColor, bold = true),
                    )
                    .addContent(text(" W", 13f, VictronPalette.TEXT_DIM))
                    .build(),
            )
            .addContent(spacer(1f))
            .addContent(text(state, 12f, stateColor, maxLines = 1))
            .addContent(spacer(4f))
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(text(Formatting.volts(values?.batteryVoltage), 14f, VictronPalette.BATTERY))
                    .addContent(text("  ", 14f, VictronPalette.TEXT_DIM))
                    .addContent(
                        text(
                            Formatting.amps(values?.batteryCurrent),
                            14f,
                            VictronPalette.currentColor(values?.batteryCurrent),
                        ),
                    )
                    .build(),
            )
            .addContent(spacer(2f))
            .addContent(
                text(
                    "${Formatting.energy(values?.yieldTodayWh)} · ${Formatting.age(snapshot, now)}",
                    12f,
                    VictronPalette.YIELD,
                ),
            )
            .build()
    }

    private fun arc(sweepDegrees: Float, color: Int): LayoutElementBuilders.Arc =
        LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(ARC_ANCHOR))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(sweepDegrees))
                    .setThickness(dp(ARC_THICKNESS_DP))
                    .setColor(argb(color))
                    .build(),
            )
            .build()

    private fun clickableToApp(context: Context): ModifiersBuilders.Modifiers =
        ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId("open_app")
                    .setOnClick(
                        ActionBuilders.LaunchAction.Builder()
                            .setAndroidActivity(
                                ActionBuilders.AndroidActivity.Builder()
                                    .setPackageName(context.packageName)
                                    .setClassName(MainActivity::class.java.name)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        maxLines: Int = 2,
    ): LayoutElementBuilders.Text = LayoutElementBuilders.Text.Builder()
        .setText(value)
        .setMaxLines(maxLines)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(sp(sizeSp))
                .setColor(argb(color))
                .setWeight(
                    if (bold) {
                        LayoutElementBuilders.FONT_WEIGHT_BOLD
                    } else {
                        LayoutElementBuilders.FONT_WEIGHT_NORMAL
                    },
                )
                .build(),
        )
        .build()

    private fun spacer(heightDp: Float): LayoutElementBuilders.Spacer =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(heightDp)).build()
}
