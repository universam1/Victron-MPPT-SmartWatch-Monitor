package de.universam.victron.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import de.universam.victron.data.model.AppConfig
import de.universam.victron.data.model.DeviceConfig
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.HistoryCache
import de.universam.victron.data.model.ReadingHistory
import de.universam.victron.data.model.SnapshotCache
import de.universam.victron.data.model.localDayIndex
import de.universam.victron.data.sync.ConfigSync
import de.universam.victron.protocol.DecodeResult
import de.universam.victron.protocol.ParseResult
import de.universam.victron.protocol.VictronAdvertisement
import de.universam.victron.protocol.VictronCipher
import de.universam.victron.protocol.VictronDecoder
import de.universam.victron.protocol.VictronHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single source of truth for the apps: decodes advertisements, keeps the newest snapshot per
 * device in memory, and persists them so a freshly started tile or app has something to show.
 *
 * Scans are always driven by a caller that owns their lifetime — nothing here starts a permanent
 * scan. On a watch a continuous scan is the difference between "lasts the day" and "dead by
 * lunch".
 */
public class VictronRepository internal constructor(
    private val context: Context,
    private val scanner: VictronScanner,
    private val configStore: DataStore<AppConfig>,
    private val snapshotStore: DataStore<SnapshotCache>,
    private val historyStore: DataStore<HistoryCache>,
) {

    private val _snapshots = MutableStateFlow<Map<String, DeviceSnapshot>>(emptyMap())

    /** Trend buffer per device, spanning the runtime within the current day. */
    private val _history = MutableStateFlow<Map<String, ReadingHistory>>(emptyMap())

    /** Newest snapshot per BLE address, keyed by uppercase address. */
    public val snapshots: StateFlow<Map<String, DeviceSnapshot>> = _snapshots.asStateFlow()

    /** Value history per device address, persisted across restarts but truncated daily. */
    public val history: StateFlow<Map<String, ReadingHistory>> = _history.asStateFlow()

    public val config: Flow<AppConfig> = configStore.data

    public val devices: Flow<List<DeviceConfig>> = configStore.data.map { it.devices }

    private var cacheLoaded = false

    /** Fills [snapshots] from disk. Cheap, idempotent, safe to call from a tile request. */
    public suspend fun loadCachedSnapshots() {
        if (cacheLoaded) return
        val cached = snapshotStore.data.first().snapshots
        if (cached.isNotEmpty() && _snapshots.value.isEmpty()) {
            _snapshots.value = cached.associateBy { it.address.uppercase() }
        }
        // Load persisted history, discarding data from previous days.
        val today = localDayIndex(System.currentTimeMillis())
        val stored = historyStore.data.first().devices
        if (stored.isNotEmpty() && _history.value.isEmpty()) {
            _history.value = stored.filterValues { it.dayIndex == today }
        }
        cacheLoaded = true
    }

    public fun canScan(): ScanUnavailable? = scanner.canScan()

    /**
     * Scans for [durationMillis] and updates [snapshots] while doing so.
     *
     * [ScanAggressiveness.LowLatency] is right here and nowhere else: the window is short and
     * bounded, so time to first packet is what decides whether the user sees fresh values at all.
     *
     * @return the number of distinct readings that produced a snapshot — repeats of the same
     *   reading are filtered out, so this counts readings and not radio receptions.
     */
    public suspend fun scanOnce(
        durationMillis: Long,
        aggressiveness: ScanAggressiveness = ScanAggressiveness.LowLatency,
    ): Int {
        loadCachedSnapshots()
        var count = 0
        withTimeoutOrNull(durationMillis) {
            collectAdvertisements(aggressiveness) { count++ }
        }
        if (count > 0) {
            persistSnapshots()
            persistHistory()
        }
        return count
    }

    /**
     * Collects advertisements until the caller's coroutine is cancelled, keeping [snapshots] up
     * to date. Use this while a screen is visible.
     *
     * Defaults to [ScanAggressiveness.Balanced] on purpose: this scan lasts as long as the screen,
     * and [ScanAggressiveness.LowLatency] would keep the receiver on at a 100 % duty cycle for all
     * of it. A Victron advertises about once a second, which `Balanced` catches within seconds.
     */
    public suspend fun collectAdvertisements(
        aggressiveness: ScanAggressiveness = ScanAggressiveness.Balanced,
        onSnapshot: (DeviceSnapshot) -> Unit = {},
    ) {
        val currentConfig = configStore.data.first()
        val keys = currentConfig.devices.mapNotNull { device ->
            runCatching { device.address.uppercase() to VictronCipher.parseKey(device.advertisementKeyHex) }
                .onFailure { Log.w(TAG, "Ignoring malformed key for ${device.address}") }
                .getOrNull()
        }.toMap()

        // A Victron repeats each reading on all three advertising channels and the stack reports
        // every reception, so the same reading arrives several times per second. The nonce is the
        // device's own data counter, so it identifies a reading: skipping repeats of the one we
        // just handled drops the redundant AES pass, snapshot map copy and history append without
        // ever delaying a genuinely new reading. Rate limiting by time cannot make that promise.
        //
        // Local to the scan session on purpose — a restart (a key was saved, say) has to
        // re-process whatever is currently on the air.
        val lastReading = HashMap<String, Int>()

        scanner.advertisements(aggressiveness).retryWhen { cause, attempt ->
            if (cause is ScanUnavailableException && attempt < 3) {
                Log.w(TAG, "Scan failed (attempt $attempt), retrying: ${cause.reason}")
                delay(2000L * (attempt + 1))
                true
            } else {
                false
            }
        }.collect { raw ->
            val header = (VictronAdvertisement.parse(raw.manufacturerData) as? ParseResult.Success)?.header
                ?: return@collect
            val address = raw.address.uppercase()
            // Record type included because nothing promises one counter per device, only that a
            // given reading keeps its counter.
            val reading = (header.recordTypeCode shl 16) or header.nonce
            if (lastReading.put(address, reading) == reading) return@collect

            val snapshot = decode(raw, header, currentConfig, keys) ?: return@collect
            _snapshots.value = _snapshots.value + (address to snapshot.carryOver(_snapshots.value[address]))
            appendHistory(address, snapshot)
            onSnapshot(snapshot)
        }
        // Persist trend data when the scan session ends (caller cancellation or normal completion).
        persistHistory()
    }

    /** Writes the current snapshots to disk so the tile survives a process death. */
    public suspend fun persistSnapshots() {
        val current = _snapshots.value.values.toList()
        snapshotStore.updateData { SnapshotCache(current) }
    }

    private var historyDirty = false

    private fun appendHistory(address: String, snapshot: DeviceSnapshot) {
        val values = snapshot.solarCharger ?: return
        val current = _history.value
        val h = current[address] ?: ReadingHistory()
        _history.value = current + (address to h.append(values, snapshot.receivedAtEpochMillis))
        historyDirty = true
    }

    /**
     * Persists the trend history to disk. Called when a scan stops (after [scanOnce] completes and
     * at each [collectAdvertisements] cancellation) so disk writes are bounded by scan sessions,
     * not by advertisement rate.
     */
    public suspend fun persistHistory() {
        if (!historyDirty) return
        historyDirty = false
        historyStore.updateData { HistoryCache(_history.value) }
    }

    private fun decode(
        raw: RawAdvertisement,
        header: VictronHeader,
        config: AppConfig,
        keys: Map<String, ByteArray>,
    ): DeviceSnapshot? {
        val address = raw.address.uppercase()
        // Prefer the key configured for this address. If none matches, fall back to any
        // configured key whose first byte equals the advertisement's key-check byte — that keeps
        // decoding alive when a device shows up under a different address than configured.
        val key = keys[address]
            ?: keys.values.firstOrNull { VictronCipher.matches(it, header) }

        val result = VictronDecoder.decode(header, key)
        if (result is DecodeResult.Unusable) return null

        return DeviceSnapshot.from(
            address = raw.address,
            bleName = raw.bleName,
            label = config.labelFor(raw.address),
            rssi = raw.rssi,
            receivedAtEpochMillis = raw.receivedAtEpochMillis,
            result = result,
        )
    }

    // ---- configuration -------------------------------------------------------------------

    public suspend fun upsertDevice(device: DeviceConfig) {
        val stamped = device.copy(updatedAtEpochMillis = System.currentTimeMillis())
        val merged = configStore.updateData { config ->
            val others = config.devices.filterNot { it.address.equals(stamped.address, ignoreCase = true) }
            config.copy(devices = others + stamped)
        }
        // Keep the in-memory snapshot label consistent with the new configuration.
        _snapshots.value = _snapshots.value.mapValues { (_, snapshot) ->
            if (snapshot.address.equals(stamped.address, ignoreCase = true)) {
                snapshot.copy(label = stamped.label)
            } else {
                snapshot
            }
        }
        ConfigSync.push(context, merged.devices, System.currentTimeMillis())
    }

    public suspend fun removeDevice(address: String) {
        configStore.updateData { config ->
            config.copy(devices = config.devices.filterNot { it.address.equals(address, ignoreCase = true) })
        }
        // Not pushed on purpose: the sync is a union merge, so a removal cannot be expressed.
        // Remove the device on the other side too if you want it gone there.
    }

    public suspend fun setBackgroundScanEnabled(enabled: Boolean) {
        configStore.updateData { it.copy(backgroundScanEnabled = enabled) }
    }

    public suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        configStore.updateData { it.copy(autoUpdateEnabled = enabled) }
    }

    public suspend fun setScanWindowSeconds(seconds: Int) {
        configStore.updateData { it.copy(scanWindowSeconds = seconds.coerceIn(5, 60)) }
    }

    public suspend fun setKeepScreenOnMinutes(minutes: Int) {
        configStore.updateData { it.copy(keepScreenOnMinutes = minutes.coerceIn(0, 60)) }
    }

    // ---- phone <-> watch sync ------------------------------------------------------------

    /**
     * Merges a device list received from the counterpart device and, if this side knows more,
     * publishes the merge result so both ends converge.
     */
    public suspend fun mergeSyncedDevices(remote: List<DeviceConfig>) {
        if (remote.isEmpty()) return
        val merged = configStore.updateData { it.mergeDevices(remote) }
        Log.d(TAG, "Merged ${remote.size} synced device(s); now ${merged.devices.size}")
        if (merged.hasNewerThan(remote)) {
            ConfigSync.push(context, merged.devices, System.currentTimeMillis())
        }
    }

    /** Pulls whatever the counterpart published and pushes the local list. Call it on app start. */
    public suspend fun syncNow() {
        mergeSyncedDevices(ConfigSync.pull(context))
        ConfigSync.push(context, configStore.data.first().devices, System.currentTimeMillis())
    }

    internal companion object {
        private const val TAG = "VictronRepository"
    }
}
