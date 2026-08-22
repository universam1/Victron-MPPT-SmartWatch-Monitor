package de.universam.victron.data

import android.content.Context
import de.universam.victron.data.store.createConfigStore
import de.universam.victron.data.store.createSnapshotStore
import de.universam.victron.data.update.UpdateManager

/**
 * Hand-rolled service locator. The whole app graph is a repository and an updater, so a DI
 * framework would be more machinery than the thing it wires up.
 */
public object VictronData {

    @Volatile
    private var repository: VictronRepository? = null

    @Volatile
    private var updates: UpdateManager? = null

    /**
     * Called when new data landed — after a background scan, or after the counterpart device
     * synced its configuration. The watch app uses it to ask the platform for a tile update. Set it
     * from `Application.onCreate`, which also runs in the WorkManager and Data Layer processes.
     */
    @Volatile
    public var refreshSurfaces: (suspend (Context) -> Unit)? = null

    public fun repository(context: Context): VictronRepository {
        repository?.let { return it }
        return synchronized(this) {
            repository ?: run {
                val appContext = context.applicationContext
                VictronRepository(
                    context = appContext,
                    scanner = VictronScanner(appContext),
                    configStore = createConfigStore(appContext),
                    snapshotStore = createSnapshotStore(appContext),
                ).also { repository = it }
            }
        }
    }

    /**
     * The self updater. A singleton like the repository, because the install-result receiver, the
     * background worker and the UI all have to observe the *same* [UpdateManager.state].
     */
    public fun updates(context: Context): UpdateManager {
        updates?.let { return it }
        return synchronized(this) {
            updates ?: UpdateManager(context.applicationContext).also { updates = it }
        }
    }
}
