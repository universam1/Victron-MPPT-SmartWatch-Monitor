package de.universam.victron.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import de.universam.victron.protocol.VictronBle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** One raw Victron advertisement as it came off the air. */
public class RawAdvertisement(
    public val address: String,
    public val bleName: String?,
    public val rssi: Int,
    public val manufacturerData: ByteArray,
    public val receivedAtEpochMillis: Long,
)

/**
 * Scan aggressiveness — the one knob that actually decides what a scan costs.
 *
 * The receiver duty cycle is fixed here, at `startScan` time; nothing downstream can undo it.
 * Throwing advertisements away in the callback saves a decode, not a milliamp: by then the radio
 * has already listened and the process has already been woken. AOSP's default windows:
 *
 * | Mode | Window / interval | Duty cycle | Time to first packet from a 1 Hz advertiser |
 * |---|---|---|---|
 * | [LowPower] | 512 ms / 5120 ms | ~10 % | ~10 s |
 * | [Balanced] | 1024 ms / 4096 ms | ~25 % | a few seconds |
 * | [LowLatency] | 4096 ms / 4096 ms | 100 % | immediate |
 *
 * Devices may override those numbers, but the ratios hold. [Balanced] is the right default for a
 * scan that runs for as long as a screen is visible; [LowLatency] is for short bounded windows
 * where time to first packet *is* the feature, i.e. the tile refresh and the "scan now" button.
 */
public enum class ScanAggressiveness(internal val settingsMode: Int) {
    LowPower(ScanSettings.SCAN_MODE_LOW_POWER),
    Balanced(ScanSettings.SCAN_MODE_BALANCED),
    LowLatency(ScanSettings.SCAN_MODE_LOW_LATENCY),
}

/** Something that keeps a scan from starting. */
public sealed interface ScanUnavailable {
    public data object NoPermission : ScanUnavailable
    public data object BluetoothOff : ScanUnavailable
    public data object NoLeSupport : ScanUnavailable
    public data class Failed(val errorCode: Int) : ScanUnavailable
}

public class ScanUnavailableException(public val reason: ScanUnavailable) :
    IllegalStateException("BLE scan unavailable: $reason")

/**
 * Turns Victron product advertisements into a cold [Flow].
 *
 * The advertisements are filtered in the Bluetooth controller (manufacturer id `0x02E1` and a
 * first payload byte of `0x10`), so the app is only woken for packets it actually wants.
 */
public class VictronScanner(context: Context) {

    private val appContext = context.applicationContext

    public fun canScan(): ScanUnavailable? {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(appContext, permission) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ScanUnavailable.NoPermission
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return ScanUnavailable.NoLeSupport
        if (!adapter.isEnabled) return ScanUnavailable.BluetoothOff
        if (adapter.bluetoothLeScanner == null) return ScanUnavailable.NoLeSupport
        return null
    }

    /**
     * Emits every matching advertisement until the collector is cancelled.
     *
     * @throws ScanUnavailableException if scanning cannot be started (or fails right away).
     */
    @SuppressLint("MissingPermission") // checked in canScan() before startScan()
    public fun advertisements(aggressiveness: ScanAggressiveness): Flow<RawAdvertisement> = callbackFlow {
        canScan()?.let { throw ScanUnavailableException(it) }

        val scanner = appContext.getSystemService(BluetoothManager::class.java)!!.adapter.bluetoothLeScanner
            ?: throw ScanUnavailableException(ScanUnavailable.NoLeSupport)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val manufacturerData = result.scanRecord
                    ?.getManufacturerSpecificData(VictronBle.MANUFACTURER_ID)
                    ?: return
                trySend(
                    RawAdvertisement(
                        address = result.device.address,
                        bleName = result.scanRecord?.deviceName,
                        rssi = result.rssi,
                        manufacturerData = manufacturerData,
                        receivedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed with error $errorCode")
                close(ScanUnavailableException(ScanUnavailable.Failed(errorCode)))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(aggressiveness.settingsMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()

        scanner.startScan(listOf(VICTRON_FILTER), settings, callback)
        Log.d(TAG, "BLE scan started ($aggressiveness)")

        awaitClose {
            runCatching { scanner.stopScan(callback) }
                .onFailure { Log.w(TAG, "stopScan failed", it) }
            Log.d(TAG, "BLE scan stopped")
        }
    }.buffer(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private companion object {
        private const val TAG = "VictronScanner"

        /** Manufacturer id 0x02E1, first data byte must be 0x10 (product advertisement). */
        private val VICTRON_FILTER: ScanFilter = ScanFilter.Builder()
            .setManufacturerData(
                VictronBle.MANUFACTURER_ID,
                byteArrayOf(VictronBle.PRODUCT_ADVERTISEMENT.toByte()),
                byteArrayOf(0xFF.toByte()),
            )
            .build()
    }
}
