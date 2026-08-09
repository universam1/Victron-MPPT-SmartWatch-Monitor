package de.universam.victron.data

import android.content.Context
import de.universam.victron.data.store.createConfigStore
import de.universam.victron.data.store.createSnapshotStore

/**
 * Hand-rolled service locator. The whole app graph is three objects, so a DI framework would be
 * more machinery than the thing it wires up.
 */
public object VictronData {

    @Volatile
    private var repository: VictronRepository? = null

    /**
     * Called after a background scan finished, so an app can push its own surfaces (the watch app
     * uses it to ask the platform for a tile update). Set it from `Application.onCreate`, which
     * also runs in the WorkManager process.
     */
    @Volatile
    public var onScanFinished: (suspend (Context) -> Unit)? = null

    public fun repository(context: Context): VictronRepository {
        repository?.let { return it }
        return synchronized(this) {
            repository ?: run {
                val appContext = context.applicationContext
                VictronRepository(
                    scanner = VictronScanner(appContext),
                    configStore = createConfigStore(appContext),
                    snapshotStore = createSnapshotStore(appContext),
                ).also { repository = it }
            }
        }
    }
}
