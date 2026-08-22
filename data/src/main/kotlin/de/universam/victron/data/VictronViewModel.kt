package de.universam.victron.data

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.universam.victron.data.model.AppConfig
import de.universam.victron.data.model.DeviceConfig
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.ReadingHistory
import de.universam.victron.data.model.SnapshotStatus
import de.universam.victron.protocol.VictronCipher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the scanner is doing right now. */
public sealed interface ScanState {
    public data object Idle : ScanState
    public data object Scanning : ScanState
    public data class Unavailable(val reason: ScanUnavailable) : ScanState
}

/** Result of a manual sync operation. */
public sealed interface SyncResult {
    public data object Idle : SyncResult
    public data object Syncing : SyncResult
    public data class Done(val deviceCount: Int) : SyncResult
    public data class Failed(val message: String) : SyncResult
}

/**
 * Shared between the watch app and the phone app — both show the same data and offer the same
 * actions, so there is exactly one place where scanning and configuration live.
 */
public open class VictronViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VictronData.repository(application)

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    public val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _syncState = MutableStateFlow<SyncResult>(SyncResult.Idle)
    public val syncState: StateFlow<SyncResult> = _syncState.asStateFlow()

    /**
     * Devices sorted so the useful ones come first: decoded, then discovered, then stale.
     *
     * Throttled ahead of the sort so neither the sort nor the recomposition it triggers runs more
     * than [UI_UPDATE_WINDOW_MILLIS] apart. Every emission redraws the arc gauges, which are
     * sweep-gradient `Canvas` work — worth doing when the reading changed, wasteful several times
     * a second. The repository keeps recording every reading; only what the UI observes is capped.
     */
    public val snapshots: StateFlow<List<DeviceSnapshot>> = repository.snapshots
        .throttleLatest(UI_UPDATE_WINDOW_MILLIS)
        .map { snapshots ->
            snapshots.values.sortedWith(
                compareBy(
                    { it.status != SnapshotStatus.DECODED },
                    { it.displayName },
                ),
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    public val config: StateFlow<AppConfig> = repository.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig())

    /**
     * Value history per device address, for the trend graphs and the arcs' peak marks.
     *
     * Throttled like [snapshots] — a sparkline redraw per reading is the same wasted work. The
     * buffer behind it still gets every reading, and it decimates by keeping each bucket's extreme,
     * so no peak is missing from the curve.
     */
    public val history: StateFlow<Map<String, ReadingHistory>> = repository.history
        .throttleLatest(UI_UPDATE_WINDOW_MILLIS)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            repository.loadCachedSnapshots()
            // Pick up whatever the counterpart device published while this app was closed.
            repository.syncNow()
        }
    }

    /** Exchanges the device list with the paired phone/watch right now. */
    public fun syncNow() {
        viewModelScope.launch {
            _syncState.value = SyncResult.Syncing
            try {
                repository.syncNow()
                val count = config.value.devices.size
                _syncState.value = SyncResult.Done(count)
            } catch (e: Exception) {
                _syncState.value = SyncResult.Failed(e.message ?: "Unknown error")
            }
            delay(2_000)
            _syncState.value = SyncResult.Idle
        }
    }

    /**
     * Starts scanning until [stopLiveScan] or the ViewModel dies. Safe to call repeatedly.
     *
     * This runs for as long as a screen is visible, so it takes the [ScanAggressiveness.Balanced]
     * duty cycle. Do not raise it to [ScanAggressiveness.LowLatency] to make the gauge livelier:
     * the values only change once a second anyway, and a 100 % duty cycle receiver for the whole
     * screen-on time is the single most expensive thing this app can do.
     */
    public fun startLiveScan(aggressiveness: ScanAggressiveness = ScanAggressiveness.Balanced) {
        if (scanJob?.isActive == true) return
        repository.canScan()?.let {
            _scanState.value = ScanState.Unavailable(it)
            return
        }
        _scanState.value = ScanState.Scanning
        scanJob = viewModelScope.launch {
            try {
                repository.collectAdvertisements(aggressiveness)
            } catch (unavailable: ScanUnavailableException) {
                _scanState.value = ScanState.Unavailable(unavailable.reason)
            } catch (error: SecurityException) {
                Log.w(TAG, "Scan denied", error)
                _scanState.value = ScanState.Unavailable(ScanUnavailable.NoPermission)
            } finally {
                withContext(NonCancellable) { repository.persistSnapshots() }
                if (_scanState.value == ScanState.Scanning) _scanState.value = ScanState.Idle
            }
        }
    }

    public fun stopLiveScan() {
        scanJob?.cancel()
        scanJob = null
        if (_scanState.value == ScanState.Scanning) _scanState.value = ScanState.Idle
    }

    /** Re-checks permission / Bluetooth state and starts over. */
    public fun retryScan() {
        stopLiveScan()
        _scanState.value = ScanState.Idle
        startLiveScan()
    }

    /**
     * Stores an advertisement key for a device.
     *
     * @return false when the key is not 32 hex characters.
     */
    public fun saveKey(address: String, keyHex: String, label: String? = null): Boolean {
        val normalized = keyHex.filterNot { it == ' ' || it == ':' || it == '-' }.lowercase()
        if (runCatching { VictronCipher.parseKey(normalized) }.isFailure) return false
        viewModelScope.launch {
            val existing = config.value.deviceFor(address)
            repository.upsertDevice(
                existing?.copy(advertisementKeyHex = normalized, label = label ?: existing.label)
                    ?: DeviceConfig(address = address, advertisementKeyHex = normalized, label = label),
            )
            // Pick the new key up immediately.
            if (scanJob?.isActive == true) {
                stopLiveScan()
                startLiveScan()
            }
        }
        return true
    }

    public fun removeDevice(address: String) {
        viewModelScope.launch { repository.removeDevice(address) }
    }

    public fun setBackgroundScanEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setBackgroundScanEnabled(enabled)
            ScanScheduler.setPeriodicScanEnabled(getApplication<Application>(), enabled)
        }
    }

    /** One-shot background scan, e.g. from a "scan now" button. */
    public fun requestScanNow() {
        ScanScheduler.requestScanNow(getApplication<Application>())
    }

    public fun snapshotFor(address: String): DeviceSnapshot? =
        snapshots.value.firstOrNull { it.address.equals(address, ignoreCase = true) }

    public fun keyFor(address: String): String? = config.value.keyFor(address)

    override fun onCleared() {
        stopLiveScan()
        super.onCleared()
    }

    private companion object {
        private const val TAG = "VictronViewModel"

        /** 2 Hz — fast enough to look live for a value that updates about once a second. */
        private const val UI_UPDATE_WINDOW_MILLIS = 500L
    }
}
