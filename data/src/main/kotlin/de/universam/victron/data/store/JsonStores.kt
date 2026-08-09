package de.universam.victron.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import de.universam.victron.data.model.AppConfig
import de.universam.victron.data.model.SnapshotCache
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Minimal JSON-backed [Serializer]; a corrupt file falls back to defaults instead of crashing. */
internal class JsonSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T,
) : Serializer<T> {

    override suspend fun readFrom(input: InputStream): T =
        try {
            json.decodeFromString(serializer, input.readBytes().decodeToString())
        } catch (_: SerializationException) {
            defaultValue
        } catch (_: IllegalArgumentException) {
            defaultValue
        }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(json.encodeToString(serializer, t).encodeToByteArray())
    }
}

internal fun createConfigStore(context: Context): DataStore<AppConfig> =
    DataStoreFactory.create(
        serializer = JsonSerializer(AppConfig.serializer(), AppConfig()),
        produceFile = { context.dataStoreFile("victron-config.json") },
    )

internal fun createSnapshotStore(context: Context): DataStore<SnapshotCache> =
    DataStoreFactory.create(
        serializer = JsonSerializer(SnapshotCache.serializer(), SnapshotCache()),
        produceFile = { context.dataStoreFile("victron-snapshots.json") },
    )
