package pl.kret.w9.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.kret.w9.App
import pl.kret.w9.RideProfile
import pl.kret.w9.ble.ConnState

val MODE_NAMES = listOf("Spacer", "Eko", "Normal", "Sport", "Sport+", "Sport++")
val KERS_NAMES = listOf("Adaptacyjny", "Słaby", "Średni", "Mocny")

@Composable
fun ControlsScreen() {
    val app = App.instance
    val s = app.scooter
    val state by s.state.collectAsStateWithLifecycle()
    val t by s.telemetry.collectAsStateWithLifecycle()
    val stats by s.analytics.stats.collectAsStateWithLifecycle()
    val connected = state == ConnState.CONNECTED
    val moving = t.speedKmh > 0.5

    val lmin = (t.limitMin ?: 6).coerceAtLeast(1)
    val lmax = (t.limitMax ?: 45).coerceAtLeast(lmin + 1)
    var limit by remember { mutableFloatStateOf((t.speedLimitKmh ?: 25).toFloat()) }
    LaunchedEffect(t.speedLimitKmh) { t.speedLimitKmh?.let { if (it > 0) limit = it.toFloat() } }
    var pin by remember { mutableStateOf(app.prefs.pin) }

    // dostępne tryby jazdy (z ramki 0x21); gdy brak informacji – 4 podstawowe
    val modes = (0 until 6).filter { t.gearsAvailable and (1 shl it) != 0 }.ifEmpty { listOf(0, 1, 2, 3) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LargeTitle("Sterowanie")
        if (!connected) Text("Połącz się z hulajnogą na zakładce Licznik.", color = Ui.Warn)
        if (moving) Text("Tryb, limit i blokadę hulajnoga zmienia tylko na postoju.", color = Ui.Warn, fontSize = 13.sp)

        Panel(title = "Profile jazdy (tryb + limit + KERS + start)") {
            val profiles = RideProfile.PRESETS + app.prefs.customProfile
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { pr ->
                    val active = t.speedMode == pr.gear && t.speedLimitKmh == pr.limitKmh && t.kersLevel == pr.kers
                    val bg by androidx.compose.animation.animateColorAsState(if (active) Ui.Accent else Ui.Card2, label = "prof")
                    androidx.compose.foundation.layout.Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).background(bg)
                            .bounceClick { s.applyProfile(pr); limit = pr.limitKmh.toFloat() },
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) { Text(pr.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (active) Color.Black else Color.White) }
                }
            }
            Text(
                profiles.joinToString("\n") {
                    "${it.name}: ${MODE_NAMES.getOrElse(it.gear) { "${it.gear}" }}, ${it.limitKmh} km/h, KERS ${KERS_NAMES.getOrElse(it.kers) { "${it.kers}" }}" +
                        if (it.zeroStart) ", zero start" else ""
                },
                color = Ui.Muted, fontSize = 11.sp,
            )
            Text(
                "Zapisz obecne ustawienia jako „Własny”", color = Ui.Blue, fontSize = 15.sp,
                modifier = Modifier.bounceClick {
                    app.prefs.customProfile = RideProfile("Własny", t.speedMode ?: 2, limit.toInt(), t.kersLevel ?: 0, t.zeroStart)
                },
            )
        }

        Panel(title = "Tryb jazdy") {
            Segmented(modes.map { MODE_NAMES.getOrElse(it) { "$it" } }, modes.indexOf(t.speedMode ?: -1)) { s.setGear(modes[it]) }
        }

        Panel(title = "Blokada") {
            Text(if (t.locked) "Zablokowana" else "Odblokowana", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (t.locked) Ui.Danger else Ui.Accent)
            if (t.lockHasPin) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> if (v.length <= 4 && v.all(Char::isDigit)) { pin = v; app.prefs.pin = v } },
                    label = { Text("PIN hulajnogi (4 cyfry)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PillButton(
                if (t.locked) "Odblokuj" else "Zablokuj", Modifier.fillMaxWidth(),
                color = if (t.locked) Ui.Accent else Ui.Danger, textColor = if (t.locked) Color.Black else Color.White,
            ) { if (t.locked) s.unlock(pin) else s.lock() }
            SwitchRow("Smart lock", t.smartLock, "automatyczna blokada") { s.setSmartLock(it) }
        }

        Panel(title = "Światła i jazda") {
            SwitchRow("Światło przednie", t.headlightOn) { s.setLight(it) }
            SwitchRow("Tempomat", t.cruiseOn, if (t.cruiseActive) "aktywny – trzymasz stałą prędkość" else "włącza się po kilku sekundach stałej prędkości") { s.setCruise(it) }
            SwitchRow("Start od zera (zero start)", t.zeroStart, "rusza bez odpychania nogą") { app.prefs.zeroStart = it; s.setZeroStart(it) }
        }

        Panel(title = "Limit prędkości (${Ui.speedUnit})") {
            Text(
                "${f0(Ui.speed(limit.toDouble()))}", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light,
                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally),
            )
            Slider(
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Ui.Accent, inactiveTrackColor = Ui.Track, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                value = limit.coerceIn(lmin.toFloat(), lmax.toFloat()),
                onValueChange = { limit = it },
                valueRange = lmin.toFloat()..lmax.toFloat(),
                steps = maxOf(0, lmax - lmin - 1),
            )
            Row {
                Text("min $lmin", color = Ui.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("obecnie ${t.speedLimitKmh ?: "–"} km/h", color = Ui.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("max $lmax", color = Ui.Muted, fontSize = 11.sp)
            }
            PillButton("Ustaw limit", Modifier.fillMaxWidth(), enabled = !moving) { s.setSpeedLimit(limit.toInt()) }
            Text("Zakres min–max podaje sama hulajnoga. W PL na drodze rowerowej max 20 km/h.", color = Ui.Muted, fontSize = 12.sp)
        }

        Panel(title = "Rekuperacja (KERS): ${KERS_NAMES.getOrElse(t.kersLevel ?: -1) { "–" }}") {
            Segmented(KERS_NAMES.map { it.take(6) }, t.kersLevel ?: -1) { app.prefs.kers = it; s.setEBrake(it) }
            Text(
                when (t.kersLevel) {
                    0 -> "Adaptacyjny – sterownik sam dobiera siłę hamowania silnikiem."
                    1 -> "Słaby – hulajnoga toczy się swobodnie po puszczeniu gazu."
                    2 -> "Średni – dobry kompromis do miasta."
                    3 -> "Mocny – najwięcej odzysku i mniejsze zużycie klocków; uważaj na mokrym."
                    else -> "Wybierz poziom."
                },
                color = Ui.Muted, fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Odzyskano", "${f1(stats.regenWh)} Wh", Modifier.weight(1f), Ui.Accent)
                Stat("Max prąd regen.", "${f1(stats.peakRegenA)} A", Modifier.weight(1f))
                Stat("Odzysk", if (stats.energyWh > 1) "${f0(stats.regenWh / stats.energyWh * 100)}%" else "–", Modifier.weight(1f))
            }
            Text("Porównaj % odzysku na tej samej trasie przy różnych poziomach.", color = Ui.Muted, fontSize = 11.sp)
        }

        Panel(title = "Jednostki na wyświetlaczu") {
            Segmented(listOf("km/h", "mph"), if (Ui.mph.value) 1 else 0) {
                val m = it == 1
                Ui.mph.value = m
                app.prefs.mph = m
                s.setUnit(m)
            }
        }

        Panel(title = "Informacje") {
            PillButton("Odśwież dane", Modifier.fillMaxWidth(), color = Ui.Card2, textColor = Color.White) { s.queryInfo() }
            t.serial?.let { Text("Nr seryjny: $it", color = Ui.Muted) }
            t.odometerKm?.let { Text("Przebieg całkowity: ${f0(it)} km", color = Ui.Muted) }
        }
    }
}
