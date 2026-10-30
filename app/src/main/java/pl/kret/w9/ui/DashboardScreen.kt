package pl.kret.w9.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TrendingFlat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pl.kret.w9.App
import pl.kret.w9.ble.ConnState

@Composable
fun DashboardScreen() {
    val app = App.instance
    val scooter = app.scooter
    val state by scooter.state.collectAsStateWithLifecycle()
    val t by scooter.telemetry.collectAsStateWithLifecycle()
    val name by scooter.deviceName.collectAsStateWithLifecycle()
    val recording by app.recorder.recording.collectAsStateWithLifecycle()
    val peak by scooter.analytics.stats.collectAsStateWithLifecycle()
    val live by app.recorder.live.collectAsStateWithLifecycle()
    var showScan by remember { mutableStateOf(false) }
    var showHud by remember { mutableStateOf(false) }
    val alarm by scooter.alarm.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recording) { while (recording) { now = System.currentTimeMillis(); delay(1000) } }

    val connected = state == ConnState.CONNECTED
    val useGps = app.prefs.useGpsSpeed || !connected
    val speed = if (useGps && recording) live.gpsKmh else t.speedKmh

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------- nagłówek ----------
        LargeTitle(
            title = name ?: "W9 Scooter",
            subtitle = null,
            trailing = {
                AnimatedContent(
                    targetState = connected,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                    label = "connBtn",
                ) { c ->
                    Text(
                        if (c) "Rozłącz" else "Połącz",
                        color = if (c) Ui.Danger else Color.Black,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp).clip(CircleShape)
                            .background(if (c) Ui.Danger.copy(alpha = 0.16f) else Ui.Accent)
                            .bounceClick { if (c) scooter.disconnect() else { showScan = true; scooter.startScan() } }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 0.dp)) {
            val dot = when (state) {
                ConnState.CONNECTED -> Ui.Accent
                ConnState.CONNECTING, ConnState.SCANNING -> Ui.Warn
                ConnState.DISCONNECTED -> Ui.Muted
            }
            PulseDot(dot, pulsing = state != ConnState.DISCONNECTED)
            Text(
                when (state) {
                    ConnState.CONNECTED -> "Połączono" + (t.serial?.let { " · $it" } ?: "")
                    ConnState.CONNECTING -> "Łączenie…"
                    ConnState.SCANNING -> "Szukanie hulajnogi…"
                    ConnState.DISCONNECTED -> "Rozłączono"
                },
                color = Ui.Muted, fontSize = 15.sp,
            )
        }

        // ---------- alarm ----------
        if (alarm) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Danger)
                    .bounceClick { scooter.stopAlarm() }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ALARM!", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Ktoś rusza zablokowaną hulajnogą", color = Color.White, fontSize = 14.sp)
                }
                Text("Wycisz", color = Ui.Danger, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(Color.White).padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }

        // ---------- prędkościomierz ----------
        Appear(60) {
            Speedometer(
                kmh = speed, maxKmh = maxOf(50.0, ((t.limitMax ?: 45) + 5).toDouble()),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).bounceClick { showHud = true },
                label = t.speedMode?.let { MODE_NAMES.getOrNull(it) }?.takeIf { connected },
            )
        }
        Text(
            "Dotknij licznika – tryb HUD", color = Ui.Muted, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).offset(y = (-8).dp),
        )

        // ---------- szybkie akcje (jak Centrum sterowania) ----------
        Appear(120) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ControlTile(if (t.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, if (t.locked) "Zablokowana" else "Blokada", t.locked, Ui.Danger) {
                    scooter.toggleLock()
                }
                ControlTile(Icons.Rounded.FlashlightOn, "Światło", t.headlightOn, Ui.Yellow) { scooter.setLight(!t.headlightOn) }
                ControlTile(Icons.Rounded.TrendingFlat, "Tempomat", t.cruiseOn, Ui.Blue) { scooter.setCruise(!t.cruiseOn) }
                ControlTile(Icons.Rounded.Bolt, "Zero start", t.zeroStart, Ui.Purple) { scooter.setZeroStart(!t.zeroStart) }
            }
        }

        // ---------- bateria ----------
        Panel(title = "Bateria") {
            val kmPerPct = app.trips.kmPerPercent()
            val scooterRange = t.rangeKm2?.takeIf { it > 0 } ?: t.rangeKm?.takeIf { it > 0 }
            val range = scooterRange?.toDouble() ?: if (kmPerPct != null) t.batteryPct * kmPerPct else t.batteryPct * 0.40
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                    Ring(t.batteryPct / 100f, batteryColor(t.batteryPct), Modifier.fillMaxSize(), stroke = 10.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${t.batteryPct}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("%", color = Ui.Muted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("~${f0(Ui.dist(range))} ${Ui.distUnit}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text("szacowany zasięg", color = Ui.Muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        t.voltage?.let { Text("${f1(it)} V", color = Ui.Muted, fontSize = 13.sp) }
                        t.powerW?.let { Text("${f0(it)} W", color = Ui.Muted, fontSize = 13.sp) }
                    }
                    if (t.charging) Text("⚡ Ładowanie", color = Ui.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (t.lowPower) Text("Niski poziom baterii", color = Ui.Danger, fontSize = 13.sp)
                }
            }
        }

        // ---------- statystyki ----------
        Panel(title = "Jazda") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Limit", t.speedLimitKmh?.takeIf { it > 0 }?.let { "${f0(Ui.speed(it.toDouble()))}" } ?: "–", Modifier.weight(1f))
                Stat("Śr. prędk.", if (t.avgSpeedKmh > 0) f0(Ui.speed(t.avgSpeedKmh)) else "–", Modifier.weight(1f))
                Stat("Błąd", if (t.faultCode == 0) "OK" else "E${t.faultCode}", Modifier.weight(1f), if (t.faultCode == 0) Ui.Accent else Ui.Danger)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Trasa", t.tripKm?.let { "${f1(Ui.dist(it))} ${Ui.distUnit}" } ?: "–", Modifier.weight(1f))
                Stat("Przebieg", t.odometerKm?.let { "${f0(Ui.dist(it))} ${Ui.distUnit}" } ?: "–", Modifier.weight(1f))
                Stat("KERS", t.kersLevel?.let { KERS_NAMES.getOrElse(it) { "$it" }.take(6) } ?: "–", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Moc", t.powerW?.let { "${f0(it)} W" } ?: "–", Modifier.weight(1f), Ui.Accent)
                Stat("Prąd", t.currentA?.let { "${f1(it)} A" } ?: "–", Modifier.weight(1f))
                Stat("Szczyt", if (peak.peakPowerW > 0) "${f0(peak.peakPowerW)} W" else "–", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Napięcie", t.voltage?.let { "${f1(it)} V" } ?: "–", Modifier.weight(1f))
                Stat("Temp. bat.", t.batteryTempC?.let { "$it°" } ?: "–", Modifier.weight(1f))
                Stat("Temp. silnik", t.motorTempC?.let { "$it°" } ?: "–", Modifier.weight(1f))
            }
            Text(
                "Moc = napięcie × prąd baterii z hulajnogi (te same dane co w Voltix). Koło w powietrzu " +
                    "prawie nie stawia oporu, więc 50–100 W to normalny wynik; pod obciążeniem i przy ruszaniu " +
                    "zobaczysz setki watów.",
                color = Ui.Muted, fontSize = 11.sp,
            )
        }

        // ---------- nagrywanie trasy ----------
        Panel(title = "Trasa GPS") {
            if (recording) {
                val dur = (now - live.start) / 1000
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Dystans", "${f1(Ui.dist(live.distanceM / 1000))} ${Ui.distUnit}", Modifier.weight(1f))
                    Stat("Czas", duration(dur), Modifier.weight(1f))
                    Stat("Max", f0(Ui.speed(live.maxKmh)), Modifier.weight(1f))
                }
                PillButton(
                    "Zakończ i zapisz", Modifier.fillMaxWidth(), color = Ui.Danger, textColor = Color.White,
                    leading = { Icon(Icons.Rounded.Stop, null, tint = Color.White) },
                ) { app.recorder.stop() }
            } else {
                PillButton(
                    "Nagrywaj trasę", Modifier.fillMaxWidth(), color = Ui.Card2, textColor = Color.White,
                    leading = { RecordDot() },
                ) { app.recorder.start() }
            }
        }
        Spacer(Modifier.size(8.dp))
    }

    if (showScan) ScanDialog(onDismiss = { scooter.stopScan(); showScan = false })
    if (showHud) HudDialog(speed = speed, onClose = { showHud = false })
}

/**
 * Tryb HUD: ogromna prędkość na czarnym tle. Lustrzane odbicie pozwala położyć telefon pod szybką/owiewką
 * albo czytać go w odbiciu. Dotknij, żeby zamknąć.
 */
@Composable
private fun HudDialog(speed: Double, onClose: () -> Unit) {
    val app = App.instance
    val t by app.scooter.telemetry.collectAsStateWithLifecycle()
    var mirror by remember { mutableStateOf(app.prefs.hudMirror) }
    val anim by androidx.compose.animation.core.animateFloatAsState(Ui.speed(speed).toFloat(), label = "hud")
    val over = app.prefs.speedAlarmKmh > 0 && speed > app.prefs.speedAlarmKmh
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { scaleX = if (mirror) -1f else 1f },
            ) {
                Text(
                    f0(anim.toDouble()), color = if (over) Ui.Danger else Ui.Accent,
                    fontSize = 200.sp, fontWeight = FontWeight.Bold, letterSpacing = (-8).sp,
                )
                Text(Ui.speedUnit, color = Color.White, fontSize = 32.sp)
                Spacer(Modifier.size(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Text("${t.batteryPct}%", color = batteryColor(t.batteryPct), fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                    t.speedMode?.let { MODE_NAMES.getOrNull(it) }?.let {
                        Text(it.uppercase(), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(
                if (mirror) "Lustro: WŁ" else "Lustro: WYŁ", color = Ui.Muted, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
                    .bounceClick { mirror = !mirror; app.prefs.hudMirror = mirror },
            )
        }
    }
}

fun batteryColor(p: Int): Color = when {
    p <= 15 -> Ui.Danger
    p <= 35 -> Ui.Warn
    else -> Ui.Accent
}

@Composable
private fun RecordDot() {
    val tr = rememberInfiniteTransition(label = "rec")
    val a by tr.animateFloat(1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "recA")
    Icon(Icons.Rounded.FiberManualRecord, null, tint = Ui.Danger.copy(alpha = a))
}

/** Okrągły kafelek jak w Centrum sterowania iOS – aktywny podświetla się kolorem. */
@Composable
private fun ControlTile(icon: ImageVector, label: String, on: Boolean, activeColor: Color, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) activeColor else Ui.Card2, tween(250), label = "tileBg")
    val fg by animateColorAsState(if (on) Color.White else Color.White.copy(alpha = 0.9f), tween(250), label = "tileFg")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(78.dp)) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(bg).bounceClick { onClick() },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.size(6.dp))
        Text(label, color = if (on) Color.White else Ui.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun ScanDialog(onDismiss: () -> Unit) {
    val scooter = App.instance.scooter
    val results by scooter.scanResults.collectAsStateWithLifecycle()
    val state by scooter.state.collectAsStateWithLifecycle()
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) results else results.filter { it.voltix }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ui.Card,
        shape = RoundedCornerShape(22.dp),
        title = { Text("Wybierz hulajnogę", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!scooter.bluetoothOn) Text("Włącz Bluetooth w telefonie!", color = Ui.Danger)
                if (state == ConnState.SCANNING) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ui.Accent, trackColor = Ui.Card2)
                SwitchRow("Wszystkie urządzenia", showAll, "domyślnie tylko Voltix (FF60)") { showAll = it }
                if (shown.isEmpty()) Text(
                    if (state == ConnState.SCANNING) "Szukam… włącz hulajnogę i trzymaj telefon blisko." else "Nic nie znaleziono.",
                    color = Ui.Muted, modifier = Modifier.padding(vertical = 12.dp),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.address }) { d ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ui.Card2)
                                .clickable { scooter.connect(d.address, d.name); onDismiss() }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name, color = if (d.voltix) Color.White else Ui.Muted, fontWeight = FontWeight.SemiBold)
                                Text(d.address, color = Ui.Muted, fontSize = 12.sp)
                            }
                            SignalBars(d.rssi)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { scooter.startScan() }) { Text("Skanuj ponownie", color = Ui.Blue) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zamknij", color = Ui.Blue) } },
    )
}

/** Paski zasięgu sygnału jak w iOS. */
@Composable
private fun SignalBars(rssi: Int) {
    val level = when {
        rssi > -60 -> 4
        rssi > -70 -> 3
        rssi > -80 -> 2
        else -> 1
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (k in 1..4) {
            Box(
                Modifier.size(width = 4.dp, height = (k * 4).dp)
                    .clip(RoundedCornerShape(1.dp)).background(if (k <= level) Color.White else Ui.Track),
            )
        }
    }
}
