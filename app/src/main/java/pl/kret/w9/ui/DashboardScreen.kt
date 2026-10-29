package pl.kret.w9.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
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
    val gear by scooter.gear.collectAsStateWithLifecycle()
    val recording by app.recorder.recording.collectAsStateWithLifecycle()
    val live by app.recorder.live.collectAsStateWithLifecycle()
    var showScan by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recording) { while (recording) { now = System.currentTimeMillis(); delay(1000) } }

    val connected = state == ConnState.CONNECTED
    val useGps = app.prefs.useGpsSpeed || !connected
    val speed = if (useGps && recording) live.gpsKmh else t.speedKmh

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // --- pasek połączenia ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (connected) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = if (connected) Ui.Accent else Ui.Muted,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(name ?: "Brak hulajnogi", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    when (state) {
                        ConnState.CONNECTED -> "Połączono"
                        ConnState.CONNECTING -> "Łączenie…"
                        ConnState.SCANNING -> "Szukanie…"
                        ConnState.DISCONNECTED -> "Rozłączono"
                    },
                    color = Ui.Muted, fontSize = 12.sp,
                )
            }
            if (connected) OutlinedButton(onClick = { scooter.disconnect() }) { Text("Rozłącz") }
            else Button(onClick = { showScan = true; scooter.startScan() }) { Text("Połącz") }
        }

        Speedometer(kmh = speed, maxKmh = 50.0, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))

        // --- szybkie akcje ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            QuickToggle(if (t.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, if (t.locked) "Zablokowana" else "Odblokowana", t.locked) {
                if (t.locked) scooter.unlock(app.prefs.pin) else scooter.lock()
            }
            QuickToggle(Icons.Filled.FlashlightOn, "Światło", t.lightOn) { scooter.setLight(!t.lightOn) }
            QuickToggle(Icons.Filled.TrendingFlat, "Tempomat", t.cruiseOn) { scooter.setCruise(!t.cruiseOn) }
        }

        // --- bateria ---
        Panel(title = "Bateria") {
            val kmPerPct = app.trips.kmPerPercent()
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${t.batteryPct}%", color = batteryColor(t.batteryPct), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                val range = if (kmPerPct != null) t.batteryPct * kmPerPct else t.batteryPct * 0.40 // W9 ≈ 40 km
                Text("zasięg ~${f0(Ui.dist(range))} ${Ui.distUnit}", color = Ui.Muted)
            }
            LinearProgressIndicator(
                progress = { t.batteryPct / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = batteryColor(t.batteryPct),
                trackColor = Ui.Track,
            )
            if (t.charging) Text("⚡ Ładowanie", color = Ui.Accent)
        }

        // --- statystyki ---
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Bieg", "$gear", Modifier.weight(1f))
            Stat("Limit", t.speedLimitKmh?.let { "${f0(Ui.speed(it.toDouble()))} ${Ui.speedUnit}" } ?: "–", Modifier.weight(1f))
            Stat("Błąd", if (t.faultCode == 0) "OK" else "E${t.faultCode}", Modifier.weight(1f), if (t.faultCode == 0) Ui.Accent else Ui.Danger)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Napięcie", t.voltage?.let { "${f1(it)} V" } ?: "–", Modifier.weight(1f))
            Stat("Prąd", t.currentA?.let { "${f1(it)} A" } ?: "–", Modifier.weight(1f))
            Stat("Moc", t.powerW?.let { "${f0(it)} W" } ?: "–", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Temp.", t.tempC?.let { "${f0(it)} °C" } ?: "–", Modifier.weight(1f))
            Stat("Prędkość GPS", if (recording) "${f0(Ui.speed(live.gpsKmh))}" else "–", Modifier.weight(1f))
        }

        // --- nagrywanie trasy ---
        Panel(title = "Trasa") {
            if (recording) {
                val dur = (now - live.start) / 1000
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Dystans", "${f1(Ui.dist(live.distanceM / 1000))} ${Ui.distUnit}", Modifier.weight(1f))
                    Stat("Czas", duration(dur), Modifier.weight(1f))
                    Stat("Max", f0(Ui.speed(live.maxKmh)), Modifier.weight(1f))
                }
                Button(
                    onClick = { app.recorder.stop() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Ui.Danger),
                ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Zakończ i zapisz") }
            } else {
                Button(onClick = { app.recorder.start() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FiberManualRecord, null); Spacer(Modifier.width(6.dp)); Text("Start nagrywania trasy")
                }
            }
        }
    }

    if (showScan) ScanDialog(onDismiss = { scooter.stopScan(); showScan = false })
}

fun batteryColor(p: Int): Color = when {
    p <= 15 -> Ui.Danger
    p <= 35 -> Ui.Warn
    else -> Ui.Accent
}

@Composable
private fun QuickToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconToggleButton(
            checked = on,
            onCheckedChange = { onClick() },
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
        ) { Icon(icon, contentDescription = label, tint = if (on) Ui.Accent else Color.White) }
        Text(label, color = Ui.Muted, fontSize = 12.sp)
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
        title = { Text("Wybierz hulajnogę") },
        text = {
            Column {
                if (!scooter.bluetoothOn) Text("Włącz Bluetooth w telefonie!", color = Ui.Danger)
                if (state == ConnState.SCANNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                SwitchRow("Pokaż wszystkie urządzenia", showAll, "domyślnie tylko z usługą Voltix FF60") { showAll = it }
                if (shown.isEmpty()) Text(
                    if (state == ConnState.SCANNING) "Szukam… włącz hulajnogę i trzymaj telefon blisko." else "Nic nie znaleziono.",
                    color = Ui.Muted, modifier = Modifier.padding(vertical = 12.dp),
                )
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(shown, key = { it.address }) { d ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scooter.connect(d.address, d.name); onDismiss()
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name, color = if (d.voltix) Ui.Accent else Color.White, fontWeight = FontWeight.SemiBold)
                                Text(d.address + if (d.voltix) "  • Voltix" else "", color = Ui.Muted, fontSize = 12.sp)
                            }
                            Text("${d.rssi} dBm", color = Ui.Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { scooter.startScan() }) { Text("Skanuj ponownie") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
}
