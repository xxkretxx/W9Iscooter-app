package pl.kret.w9.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.kret.w9.App
import pl.kret.w9.RideProfile
import pl.kret.w9.ble.ConnState

@Composable
fun ControlsScreen() {
    val app = App.instance
    val s = app.scooter
    val state by s.state.collectAsStateWithLifecycle()
    val t by s.telemetry.collectAsStateWithLifecycle()
    val gear by s.gear.collectAsStateWithLifecycle()
    val connected = state == ConnState.CONNECTED

    var limit by remember { mutableFloatStateOf((t.speedLimitKmh ?: 25).toFloat()) }
    var kers by remember { mutableFloatStateOf(app.prefs.kers.toFloat()) }
    val stats by s.analytics.stats.collectAsStateWithLifecycle()
    var bright by remember { mutableIntStateOf(2) }
    var zeroStart by remember { mutableStateOf(app.prefs.zeroStart) }
    var smartLock by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf(app.prefs.pin) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!connected) Text("Połącz się z hulajnogą na zakładce Licznik.", color = Ui.Warn)

        Panel(title = "Profile jazdy (1 klik = bieg + limit + KERS + start)") {
            val profiles = RideProfile.PRESETS + app.prefs.customProfile
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { pr ->
                    OutlinedButton(
                        onClick = {
                            s.applyProfile(pr)
                            limit = pr.limitKmh.toFloat(); kers = pr.kers.toFloat(); zeroStart = pr.zeroStart
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                    ) { Text(pr.name, fontSize = 13.sp) }
                }
            }
            Text(
                profiles.joinToString("\n") { "${it.name}: bieg ${it.gear}, ${it.limitKmh} km/h, KERS ${it.kers}${if (it.zeroStart) ", zero start" else ""}" },
                color = Ui.Muted, fontSize = 11.sp,
            )
            TextButton(onClick = {
                app.prefs.customProfile = RideProfile("Własny", gear, limit.toInt(), kers.toInt(), zeroStart)
            }) { Text("Zapisz obecne ustawienia jako „Własny”") }
        }

        Panel(title = "Bieg (tryb prędkości)") {
            Segmented(listOf("0", "1", "2", "3"), gear) { s.setGear(it) }
            Text("Potwierdzone działanie na hulajnogach Voltix.", color = Ui.Muted, fontSize = 12.sp)
        }

        Panel(title = "Blokada") {
            OutlinedTextField(
                value = pin,
                onValueChange = { v -> if (v.length <= 4 && v.all(Char::isDigit)) { pin = v; app.prefs.pin = v } },
                label = { Text("PIN (4 cyfry, opcjonalnie)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { s.lock() }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Ui.Danger),
                ) { Text("Zablokuj") }
                Button(onClick = { s.unlock(pin) }, modifier = Modifier.weight(1f)) { Text("Odblokuj") }
            }
            SwitchRow("Smart lock", smartLock, "auto-blokada (eksperymentalne)") { smartLock = it; s.setSmartLock(it) }
        }

        Panel(title = "Światła i tempomat") {
            SwitchRow("Światło przednie", t.lightOn) { s.setLight(it) }
            SwitchRow("Tempomat", t.cruiseOn) { s.setCruise(it) }
            SwitchRow("Start od zera (zero start)", zeroStart, "rusza bez odpychania nogą") { zeroStart = it; app.prefs.zeroStart = it; s.setZeroStart(it) }
        }

        Panel(title = "Limit prędkości: ${f0(Ui.speed(limit.toDouble()))} ${Ui.speedUnit}") {
            Slider(value = limit, onValueChange = { limit = it }, valueRange = 6f..45f, steps = 38)
            OutlinedButton(onClick = { s.setSpeedLimit(limit.toInt()) }, modifier = Modifier.fillMaxWidth()) { Text("Ustaw limit") }
            Text("Pamiętaj o przepisach – w PL na drodze rowerowej max 20 km/h.", color = Ui.Muted, fontSize = 12.sp)
        }

        Panel(title = "Strojenie rekuperacji (KERS): poziom ${kers.toInt()}") {
            Slider(
                value = kers, onValueChange = { kers = it }, valueRange = 0f..5f, steps = 4,
                onValueChangeFinished = { app.prefs.kers = kers.toInt(); s.setEBrake(kers.toInt()) },
            )
            Text(
                when (kers.toInt()) {
                    0 -> "Wyłączona – hulajnoga toczy się swobodnie (najlepsze na długie zjazdy bez hamowania)."
                    1 -> "Delikatna – ledwie wyczuwalne hamowanie silnikiem po puszczeniu gazu."
                    2 -> "Średnia – dobry kompromis do miasta."
                    3 -> "Mocna – więcej odzysku, mniejsze zużycie klocków."
                    else -> "Bardzo mocna – wyraźne hamowanie silnikiem; uważaj na mokrej nawierzchni."
                },
                color = Ui.Muted, fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Odzyskano (sesja)", "${f1(stats.regenWh)} Wh", Modifier.weight(1f), Ui.Accent)
                Stat("Max prąd regen.", "${f1(stats.peakRegenA)} A", Modifier.weight(1f))
                Stat(
                    "Odzysk",
                    if (stats.energyWh > 1) "${f0(stats.regenWh / stats.energyWh * 100)}%" else "–",
                    Modifier.weight(1f),
                )
            }
            Text(
                "Porównaj % odzysku przy różnych poziomach na tej samej trasie, żeby dobrać najlepsze ustawienie. " +
                    "Zakres poziomów zależy od sterownika – jeśli któryś nie zmienia hamowania, W9 go nie obsługuje.",
                color = Ui.Muted, fontSize = 11.sp,
            )
        }

        Panel(title = "Jasność wyświetlacza") {
            Segmented(listOf("1", "2", "3"), bright - 1) { bright = it + 1; s.setBrightness(it + 1) }
        }

        Panel(title = "Jednostki") {
            Segmented(listOf("km/h", "mph"), if (Ui.mph.value) 1 else 0) {
                val m = it == 1
                Ui.mph.value = m
                app.prefs.mph = m
                s.setUnit(m)
            }
        }

        Panel(title = "Odśwież dane") {
            OutlinedButton(onClick = { s.queryInfo() }, modifier = Modifier.fillMaxWidth()) { Text("Pobierz info (serial, BMS, firmware)") }
            t.serial?.let { Text("Nr seryjny: $it", color = Ui.Muted) }
            t.firmware?.let { Text("Firmware: $it", color = Ui.Muted, fontSize = 12.sp) }
        }

        Text(
            "Funkcje poza biegiem są odtworzone z aplikacji Voltix i mogą wymagać dopasowania do W9. " +
                "Jeśli coś nie reaguje, sprawdź log w zakładce Więcej → Konsola.",
            color = Ui.Muted, fontSize = 12.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) { Text(label) }
        }
    }
}
