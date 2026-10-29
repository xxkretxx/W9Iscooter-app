package pl.kret.w9

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.kret.w9.ui.rememberHaptic
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
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
                    "Licznik" to Icons.Rounded.Speed,
                    "Sterowanie" to Icons.Rounded.Tune,
                    "Analiza" to Icons.Rounded.Insights,
                    "Trasy" to Icons.Rounded.Map,
                    "Więcej" to Icons.Rounded.MoreHoriz,
                )
                Column(Modifier.fillMaxSize().background(Ui.Bg).systemBarsPadding()) {
                    AnimatedContent(
                        targetState = tab,
                        modifier = Modifier.weight(1f),
                        transitionSpec = {
                            (fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.97f)) togetherWith
                                fadeOut(tween(160))
                        },
                        label = "tabs",
                    ) { t ->
                        Box(Modifier.fillMaxSize()) {
                            when (t) {
                                0 -> DashboardScreen()
                                1 -> ControlsScreen()
                                2 -> AnalysisScreen()
                                3 -> TripsScreen()
                                else -> MoreScreen(onKeepScreenChanged = { applyKeepScreenOn() })
                            }
                        }
                    }
                    IosTabBar(tabs, tab) { tab = it }
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

@Composable
private fun IosTabBar(tabs: List<Pair<String, ImageVector>>, selected: Int, onSelect: (Int) -> Unit) {
    val haptic = rememberHaptic()
    Column(Modifier.fillMaxWidth().background(Color(0xF2121214))) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ui.Separator))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
            tabs.forEachIndexed { i, (label, icon) ->
                val sel = i == selected
                val color by animateColorAsState(if (sel) Ui.Accent else Ui.Muted, tween(200), label = "tabc")
                val scale by animateFloatAsState(if (sel) 1.12f else 1f, spring(dampingRatio = 0.5f, stiffness = 400f), label = "tabs")
                Column(
                    Modifier.weight(1f).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { if (!sel) haptic(); onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(26.dp).scale(scale))
                    Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}
