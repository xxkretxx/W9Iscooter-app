package pl.kret.w9.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.kret.w9.App
import pl.kret.w9.ble.Voltix

@Composable
fun MoreScreen(onKeepScreenChanged: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LargeTitle("Więcej")
            Segmented(listOf("Ustawienia", "Konsola BLE"), tab) { tab = it }
        }
        if (tab == 0) SettingsTab(onKeepScreenChanged) else ConsoleTab()
    }
}

@Composable
private fun SettingsTab(onKeepScreenChanged: () -> Unit) {
    val app = App.instance
    val p = app.prefs
    var autoRe by remember { mutableStateOf(p.autoReconnect) }
    var keepOn by remember { mutableStateOf(p.keepScreenOn) }
    var gpsSpeed by remember { mutableStateOf(p.useGpsSpeed) }
    var alarm by remember { mutableFloatStateOf(p.speedAlarmKmh.toFloat()) }
    val name by app.scooter.deviceName.collectAsStateWithLifecycle()
    var bmsPoll by remember { mutableStateOf(p.bmsPoll) }
    var batAlerts by remember { mutableStateOf(p.batteryAlerts) }
    var autoLight by remember { mutableStateOf(p.autoLight) }
    var appLock by remember { mutableStateOf(p.appLock) }
    var theft by remember { mutableStateOf(p.theftAlarm) }
    var autoRec by remember { mutableStateOf(p.autoRecord) }
    val act = LocalContext.current as? pl.kret.w9.MainActivity
    val trips by app.trips.trips.collectAsStateWithLifecycle()
    val totalKm = trips.sumOf { it.distanceM } / 1000
    var svcTick by remember { mutableIntStateOf(0) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(title = "Połączenie") {
            SwitchRow("Automatyczne łączenie", autoRe, "z ostatnią hulajnogą po starcie / zerwaniu") { autoRe = it; p.autoReconnect = it }
            Text("Zapamiętana: ${name ?: p.lastAddress ?: "brak"}", color = Ui.Muted, fontSize = 13.sp)
            PillButton("Zapomnij hulajnogę", Modifier.fillMaxWidth(), color = Ui.Card2, textColor = Ui.Danger) { app.scooter.forget() }
        }

        Panel(title = "Prywatność") {
            SwitchRow(
                "Blokada aplikacji", appLock,
                if (act?.canUseAppLock() == false) "ustaw blokadę ekranu w telefonie, aby włączyć"
                else "odcisk palca, twarz lub PIN telefonu przy otwarciu",
            ) { on ->
                if (on && act?.canUseAppLock() == false) return@SwitchRow
                if (on) act?.authenticate { appLock = true; p.appLock = true }
                else act?.authenticate { appLock = false; p.appLock = false }
            }
        }

        Panel(title = "Inteligentne funkcje") {
            SwitchRow(
                "Alarm antykradzieżowy", theft,
                "gdy hulajnoga jest zablokowana i ktoś nią ruszy – syrena w telefonie + powiadomienie (telefon musi być w zasięgu Bluetooth)",
            ) { theft = it; p.theftAlarm = it; if (!it) app.scooter.stopAlarm() }
            SwitchRow("Auto-nagrywanie tras", autoRec, "start gdy ruszysz (> 5 km/h), koniec po 3 min postoju") {
                autoRec = it; p.autoRecord = it
            }
            SwitchRow("Auto-światło", autoLight,
                if (app.autoLight.available) "włącza światło po zmroku (czujnik światła telefonu)" else "brak czujnika światła w telefonie") {
                autoLight = it; p.autoLight = it; app.autoLight.update()
            }
            SwitchRow("Alerty baterii", batAlerts, "powiadomienie przy 20%, 10% i 5%") { batAlerts = it; p.batteryAlerts = it }
            SwitchRow("Odpytuj BMS", bmsPoll, "ogniwa, cykle, pojemność – co kilka sekund") { bmsPoll = it; p.bmsPoll = it }
        }

        Panel(title = "Serwis (przebieg z tras: ${f0(totalKm)} km)") {
            pl.kret.w9.SERVICE_ITEMS.forEach { item ->
                val doneAt = remember(svcTick, item.key) { p.serviceDoneAt(item.key) }
                val since = totalKm - doneAt
                val left = item.everyKm - since
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, color = Color.White, fontSize = 14.sp)
                        Text(
                            if (left <= 0) "Do zrobienia! (co ${item.everyKm} km)" else "za ${f0(left)} km (co ${item.everyKm} km)",
                            color = if (left <= 0) Ui.Warn else Ui.Muted, fontSize = 12.sp,
                        )
                    }
                    Text(
                        "Zrobione", color = Ui.Blue, fontSize = 15.sp,
                        modifier = Modifier.bounceClick { p.setServiceDone(item.key, totalKm); svcTick++ }.padding(8.dp),
                    )
                }
            }
        }

        Panel(title = "Alarm prędkości") {
            Text(
                if (alarm < 1f) "Wyłączony" else "Wibracja powyżej ${alarm.toInt()} km/h",
                color = Color.White,
            )
            Slider(value = alarm, onValueChange = { alarm = it }, onValueChangeFinished = { p.speedAlarmKmh = alarm.toInt() }, valueRange = 0f..50f)
        }

        Panel(title = "Wyświetlanie") {
            SwitchRow("Nie wygaszaj ekranu", keepOn, "przydatne na uchwycie na kierownicy") {
                keepOn = it; p.keepScreenOn = it; onKeepScreenChanged()
            }
            SwitchRow("Prędkość z GPS", gpsSpeed, "zamiast z hulajnogi (podczas nagrywania trasy)") { gpsSpeed = it; p.useGpsSpeed = it }
        }

        Panel(title = "O aplikacji") {
            Text("W9 Scooter • własny klient BLE dla hulajnóg z aplikacją Voltix (iScooter W9).", color = Color.White)
            Text(
                "Komendy i format danych odtworzone z aplikacji Voltix 1.1.1. Aplikacja nigdy nie wysyła komend " +
                    "bindowania, resetu ani aktualizacji firmware.",
                color = Ui.Muted, fontSize = 12.sp,
            )
        }
        Text("", Modifier.height(8.dp))
    }
}

@Composable
private fun ConsoleTab() {
    val s = App.instance.scooter
    val ctx = LocalContext.current
    val log by s.log.collectAsStateWithLifecycle()
    var hex by remember { mutableStateOf("") }
    var framing by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.size - 1) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.Black).padding(8.dp),
        ) {
            items(log) { l ->
                Text(
                    l.line,
                    color = when (l.dir) { '→' -> Ui.Warn; '←' -> Ui.Accent; else -> Ui.Muted },
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                )
            }
        }
        OutlinedTextField(
            value = hex, onValueChange = { hex = it },
            label = { Text(if (framing) "CMD PARAM [payload] np. 33 02 01" else "Cała ramka HEX") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, color = Color.White),
        )
        SwitchRow("Dodaj nagłówek + CRC automatycznie", framing) { framing = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { s.sendHex(hex, framing) }, modifier = Modifier.weight(1f)) { Text("Wyślij") }
            OutlinedButton(onClick = { s.queryInfo() }, modifier = Modifier.weight(1f)) { Text("Zapytania") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val cb = ctx.getSystemService(ClipboardManager::class.java)
                cb.setPrimaryClip(ClipData.newPlainText("W9 log", log.joinToString("\n") { it.line }))
                Toast.makeText(ctx, "Skopiowano log", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.weight(1f)) { Text("Kopiuj log") }
            OutlinedButton(onClick = { s.clearLog() }, modifier = Modifier.weight(1f)) { Text("Wyczyść") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { s.send(Voltix.query(Voltix.QUERY_BMS_VOLTAGE, 1), "ogniwa") }, modifier = Modifier.weight(1f)) {
                Text("BMS ogniwa", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            OutlinedButton(onClick = { s.send(Voltix.query(Voltix.QUERY_BMS_INFO, 1), "BMS info") }, modifier = Modifier.weight(1f)) {
                Text("BMS info", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        Text("", Modifier.height(4.dp))
    }
}
