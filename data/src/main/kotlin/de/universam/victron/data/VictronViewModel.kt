package de.universam.victron.data

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.universam.victron.data.model.AppConfig
import de.universam.victron.data.model.DeviceConfig
import de.universam.victron.data.model.DeviceSnapshot
import de.universam.victron.data.model.SnapshotStatus
import de.universam.victron.protocol.VictronCipher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

/**
 * Shared between the watch app and the phone app — both show the same data and offer the same
 * actions, so there is exactly one place where scanning and configuration live.
 */
public open class VictronViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VictronData.repository(application)

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    public val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /** Devices sorted so the useful ones come first: decoded, then discovered, then stale. */
    public val snapshots: StateFlow<List<DeviceSnapshot>> = repository.snapshots
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

    private var scanJob: Job? = null

    init {
        viewModelScope.launch { repository.loadCachedSnapshots() }
    }

    /** Starts scanning until [stopLiveScan] or the ViewModel dies. Safe to call repeatedly. */
    public fun startLiveScan(aggressiveness: ScanAggressiveness = ScanAggressiveness.LowLatency) {
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
            repository.upsertDevice(
                DeviceConfig(address = address, advertisementKeyHex = normalized, label = label),
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
    }
}
