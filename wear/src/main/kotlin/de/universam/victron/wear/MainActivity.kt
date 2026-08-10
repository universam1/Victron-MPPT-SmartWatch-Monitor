package de.universam.victron.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.ambient.AmbientLifecycleObserver
import de.universam.victron.wear.ui.VictronApp

class MainActivity : ComponentActivity() {

    /** Keeps the Activity alive in ambient mode so the BLE scan continues running. */
    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {}
        override fun onExitAmbient() {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        setTheme(android.R.style.Theme_DeviceDefault)
        setContent { VictronApp() }
    }
}
