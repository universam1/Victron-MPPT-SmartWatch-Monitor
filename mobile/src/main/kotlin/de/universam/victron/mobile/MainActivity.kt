package de.universam.victron.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import de.universam.victron.mobile.ui.MobileApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) {
                    darkColorScheme(primary = VictronYellow, secondary = VictronBlue)
                } else {
                    lightColorScheme(primary = VictronBlueDark, secondary = VictronBlue)
                },
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MobileApp()
                }
            }
        }
    }
}

private val VictronYellow = Color(0xFFFFC531)
private val VictronBlue = Color(0xFF4FC3F7)
private val VictronBlueDark = Color(0xFF0277BD)
