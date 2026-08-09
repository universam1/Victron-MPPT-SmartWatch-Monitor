package de.universam.victron.data.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import de.universam.victron.data.VictronData
import de.universam.victron.data.model.DeviceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What travels between phone and watch: the device list, nothing else. */
@Serializable
internal data class SyncedDevices(val devices: List<DeviceConfig>)

/**
 * Keeps the device list — the part that is annoying to type, the 32 character advertisement keys —
 * in sync between the phone app and the watch app over the Wear OS Data Layer.
 *
 * Both sides may write; per device the newer entry wins (see [de.universam.victron.data.model.AppConfig.mergeDevices]).
 * Data items persist in the Data Layer, so a watch that was switched off still picks up a key that
 * was entered on the phone hours earlier.
 *
 * **Requirement:** both APKs must share an `applicationId` and be signed with the same key,
 * otherwise the Data Layer keeps them in separate namespaces and nothing is exchanged.
 */
public object ConfigSync {

    internal const val PATH = "/victron/devices"
    internal const val KEY_DEVICES = "devices_json"
    internal const val KEY_UPDATED_AT = "updated_at"

    private const val TAG = "ConfigSync"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Publishes the local device list. Safe to call often — the Data Layer drops writes whose
     * payload is identical to what is already there.
     */
    public suspend fun push(context: Context, devices: List<DeviceConfig>, nowEpochMillis: Long) {
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putString(KEY_DEVICES, json.encodeToString(SyncedDevices.serializer(), SyncedDevices(devices)))
            dataMap.putLong(KEY_UPDATED_AT, nowEpochMillis)
        }.asPutDataRequest().setUrgent()

        runCatching { Wearable.getDataClient(context).putDataItem(request).await() }
            .onSuccess { Log.d(TAG, "Pushed ${devices.size} device(s) to the data layer") }
            .onFailure { Log.i(TAG, "Config push failed (no companion device?): ${it.message}") }
    }

    /** Reads whatever the other side published last, e.g. right after the app started. */
    public suspend fun pull(context: Context): List<DeviceConfig> = runCatching {
        val buffer = Wearable.getDataClient(context).dataItems.await()
        try {
            buffer.filter { it.uri.path == PATH }
                .flatMap { item -> decode(DataMapItem.fromDataItem(item).dataMap.getString(KEY_DEVICES)) }
        } finally {
            buffer.release()
        }
    }.onFailure { Log.i(TAG, "Config pull failed: ${it.message}") }.getOrDefault(emptyList())

    internal fun decode(payload: String?): List<DeviceConfig> {
        if (payload.isNullOrEmpty()) return emptyList()
        return runCatching { json.decodeFromString(SyncedDevices.serializer(), payload).devices }
            .getOrDefault(emptyList())
    }
}

/**
 * Receives device lists pushed by the counterpart app. Declared in this library's manifest, so both
 * the phone and the watch app get it for free.
 */
public class ConfigSyncListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onDataChanged(events: DataEventBuffer) {
        val devices = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == ConfigSync.PATH }
            .flatMap { event ->
                ConfigSync.decode(
                    DataMapItem.fromDataItem(event.dataItem).dataMap.getString(ConfigSync.KEY_DEVICES),
                )
            }
        if (devices.isEmpty()) return

        val context = applicationContext
        scope.launch {
            VictronData.repository(context).mergeSyncedDevices(devices)
            VictronData.refreshSurfaces?.invoke(context)
        }
    }
}
