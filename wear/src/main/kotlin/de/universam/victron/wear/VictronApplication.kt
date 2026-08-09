package de.universam.victron.wear

import android.app.Application
import de.universam.victron.data.VictronData
import de.universam.victron.wear.tile.VictronTileService

class VictronApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Also runs in the WorkManager process, which is what makes background scans able to
        // refresh the tile.
        VictronData.onScanFinished = { context -> VictronTileService.requestUpdate(context) }
    }
}
