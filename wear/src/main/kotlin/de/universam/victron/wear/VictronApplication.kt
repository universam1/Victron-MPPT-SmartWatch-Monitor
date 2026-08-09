package de.universam.victron.wear

import android.app.Application
import de.universam.victron.data.VictronData
import de.universam.victron.wear.tile.VictronTileService

class VictronApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Also runs in the WorkManager and Data Layer processes, which is what lets a background
        // scan — or a key that just arrived from the phone — refresh the tile.
        VictronData.refreshSurfaces = { context -> VictronTileService.requestUpdate(context) }
    }
}
