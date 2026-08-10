package de.universam.victron.wear

import android.app.Application
import de.universam.victron.data.VictronData

class VictronApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // refreshSurfaces is no longer needed (tile was removed).
        VictronData.refreshSurfaces = null
    }
}
