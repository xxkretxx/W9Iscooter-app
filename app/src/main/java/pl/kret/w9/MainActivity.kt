package pl.kret.w9

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import pl.kret.w9.ui.PillButton
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

class MainActivity : FragmentActivity() {

    /** Czy aplikacja jest odblokowana (blokada odciskiem palca / PIN-em telefonu). */
    private val unlocked = mutableStateOf(!App.instance.prefs.appLock)
    private var backgroundAt = 0L

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
                Box(Modifier.fillMaxSize()) {
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
                if (!unlocked.value) LockScreen { authenticate() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        App.instance.scooter.reconnectLast()
        val p = App.instance.prefs
        // ponowna blokada po 30 s w tle
        if (p.appLock && backgroundAt > 0 && System.currentTimeMillis() - backgroundAt > 30_000) unlocked.value = false
        if (p.appLock && !unlocked.value) authenticate()
    }

    override fun onStop() {
        super.onStop()
        backgroundAt = System.currentTimeMillis()
    }

    fun canUseAppLock(): Boolean =
        BiometricManager.from(this).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    /** Prosi o odcisk palca / twarz / PIN telefonu. onOk wywoływane po sukcesie. */
    fun authenticate(onOk: () -> Unit = {}) {
        if (!canUseAppLock()) { unlocked.value = true; onOk(); return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlocked.value = true
                onOk()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("W9 Scooter")
                .setSubtitle("Odblokuj aplikację")
                .setAllowedAuthenticators(AUTH)
                .build(),
        )
    }

    companion object {
        const val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
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

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ui.Bg).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(64.dp))
        Spacer(Modifier.size(16.dp))
        Text("W9 Scooter", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Aplikacja zablokowana", color = Ui.Muted, fontSize = 15.sp)
        Spacer(Modifier.size(32.dp))
        PillButton("Odblokuj", Modifier.padding(horizontal = 48.dp).fillMaxWidth()) { onUnlock() }
    }
}
