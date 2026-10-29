package pl.kret.w9

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import pl.kret.w9.ui.AnalysisScreen
import pl.kret.w9.ui.ControlsScreen
import pl.kret.w9.ui.DashboardScreen
import pl.kret.w9.ui.MoreScreen
import pl.kret.w9.ui.TripsScreen
import pl.kret.w9.ui.Ui
import pl.kret.w9.ui.W9Theme

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        App.instance.scooter.reconnectLast()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyKeepScreenOn()
        permLauncher.launch(requiredPermissions())

        setContent {
            W9Theme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val tabs: List<Pair<String, ImageVector>> = listOf(
                    "Licznik" to Icons.Filled.Speed,
                    "Sterowanie" to Icons.Filled.Tune,
                    "Analiza" to Icons.Filled.ShowChart,
                    "Trasy" to Icons.Filled.Map,
                    "Więcej" to Icons.Filled.Settings,
                )
                Scaffold(
                    containerColor = Ui.Bg,
                    bottomBar = {
                        NavigationBar(containerColor = Ui.Card) {
                            tabs.forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label, maxLines = 1, fontSize = 11.sp) },
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        when (tab) {
                            0 -> DashboardScreen()
                            1 -> ControlsScreen()
                            2 -> AnalysisScreen()
                            3 -> TripsScreen()
                            else -> MoreScreen(onKeepScreenChanged = { applyKeepScreenOn() })
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        App.instance.scooter.reconnectLast()
    }

    private fun applyKeepScreenOn() {
        if (App.instance.prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun requiredPermissions(): Array<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p.toTypedArray()
    }
}
