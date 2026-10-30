package pl.kret.w9.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.togetherWith
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.kret.w9.App
import pl.kret.w9.ble.AccelState
import pl.kret.w9.ble.Analytics
import pl.kret.w9.ble.ConnState
import pl.kret.w9.ble.Voltix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AnalysisScreen() {
    var tab by remember { mutableIntStateOf(0) }
    val scooter = App.instance.scooter
    // Na tym ekranie odpytujemy hulajnogę 4× na sekundę – płynne wykresy.
    DisposableEffect(Unit) {
        scooter.fastPoll = true
        onDispose { scooter.fastPoll = false }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LargeTitle("Analiza")
            Segmented(listOf("Na żywo", "Bateria", "Przyspieszenie"), tab) { tab = it }
        }
        androidx.compose.animation.AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                val sp = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                (androidx.compose.animation.slideInHorizontally(sp) { it / 6 * dir } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(sp) { -it / 6 * dir } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)))
            },
            label = "anTab",
        ) { tb ->
            when (tb) {
                0 -> ChartsTab()
                1 -> BmsTab()
                else -> AccelTab()
            }
        }
    }
}

// ------------------------------------------------------------------ wykresy

@Composable
private fun ChartsTab() {
    val a = App.instance.scooter.analytics
    val samples by a.samples.collectAsStateWithLifecycle()
    val st by a.stats.collectAsStateWithLifecycle()
    val state by App.instance.scooter.state.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state != ConnState.CONNECTED) Text("Połącz się z hulajnogą, aby zobaczyć dane na żywo.", color = Ui.Warn)
        val last = samples.lastOrNull()
        ChartPanel("Moc", last?.power?.let { "${f0(it)} W" }, samples.map { it.power }, Ui.Accent, zeroLine = true)
        ChartPanel("Natężenie prądu", last?.current?.let { "${f1(it)} A" }, samples.map { it.current }, Ui.Warn, zeroLine = true)
        ChartPanel("Napięcie pakietu", last?.voltage?.let { "${f1(it)} V" }, samples.map { it.voltage }, Color(0xFF5AA9FF))
        ChartPanel("Prędkość", last?.let { "${f0(Ui.speed(it.speed))} ${Ui.speedUnit}" }, samples.map { Ui.speed(it.speed) }, Color.White)

        Panel(title = "Sesja (od uruchomienia / resetu)") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Zużyte", "${f1(st.energyWh)} Wh", Modifier.weight(1f))
                val tel by App.instance.scooter.telemetry.collectAsStateWithLifecycle()
                // zasięg z rzeczywistego zużycia: energia w baterii / Wh na km
                val rangeKm = st.whPerKm?.takeIf { it > 1 }?.let { tel.batteryPct / 100.0 * App.instance.prefs.batteryWh / it }
                Stat("Zostało", rangeKm?.let { "${f0(Ui.dist(it))} ${Ui.distUnit}" } ?: "–", Modifier.weight(1f), Ui.Accent)
                Stat("Wh/km", st.whPerKm?.let { f1(it) } ?: "–", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Szczyt mocy", "${f0(st.peakPowerW)} W", Modifier.weight(1f))
                Stat("Szczyt prądu", "${f1(st.peakCurrentA)} A", Modifier.weight(1f))
                Stat("Czas sesji", duration((System.currentTimeMillis() - st.start) / 1000), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Dystans", "${f1(Ui.dist(st.distanceKm))} ${Ui.distUnit}", Modifier.weight(1f))
                Stat("V max", "${f0(Ui.speed(st.maxSpeed))} ${Ui.speedUnit}", Modifier.weight(1f))
            }
            PillButton("Resetuj sesję", Modifier.fillMaxWidth(), color = Ui.Card2, textColor = Ui.Danger) { a.reset() }
        }
        Text("", Modifier.height(4.dp))
    }
}

@Composable
private fun ChartPanel(title: String, value: String?, data: List<Double?>, color: Color, zeroLine: Boolean = false) {
    Panel {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, color = Ui.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(value ?: "–", color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        LineChart(data, color, zeroLine, Modifier.fillMaxWidth().height(110.dp))
        val vals = data.filterNotNull()
        if (vals.isNotEmpty()) {
            Text("min ${f1(vals.min())}  •  śr. ${f1(vals.average())}  •  max ${f1(vals.max())}", color = Ui.Muted, fontSize = 11.sp)
        } else {
            Text("Brak danych z hulajnogi (BMS może nie odpowiadać).", color = Ui.Muted, fontSize = 11.sp)
        }
    }
}

@Composable
fun LineChart(data: List<Double?>, color: Color, zeroLine: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // siatka
        for (k in 0..3) {
            val y = size.height * k / 3f
            drawLine(Ui.Track, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val pts = data.withIndex().filter { it.value != null }
        if (pts.size < 2) return@Canvas
        var lo = pts.minOf { it.value!! }
        var hi = pts.maxOf { it.value!! }
        if (zeroLine) { lo = minOf(lo, 0.0); hi = maxOf(hi, 0.0) }
        if (hi - lo < 1e-6) { hi += 1; lo -= 1 }
        val pad = (hi - lo) * 0.08
        lo -= pad; hi += pad
        val n = maxOf(data.size - 1, 1)
        fun x(i: Int) = size.width * i / n
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()

        if (zeroLine && lo < 0 && hi > 0) {
            drawLine(
                Ui.Muted, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }
        val line = Path()
        val fill = Path()
        pts.forEachIndexed { k, iv ->
            val px = x(iv.index); val py = y(iv.value!!)
            if (k == 0) { line.moveTo(px, py); fill.moveTo(px, size.height); fill.lineTo(px, py) }
            else { line.lineTo(px, py); fill.lineTo(px, py) }
        }
        fill.lineTo(x(pts.last().index), size.height)
        fill.close()
        drawPath(fill, color.copy(alpha = 0.15f))
        drawPath(line, color, style = Stroke(width = 3.dp.toPx()))
    }
}

// ------------------------------------------------------------------ BMS

@Composable
private fun BmsTab() {
    val s = App.instance.scooter
    val t by s.telemetry.collectAsStateWithLifecycle()
    val st by s.analytics.stats.collectAsStateWithLifecycle()
    val cells = t.cellVoltages()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val series = t.cellCount?.takeIf { it in 7..30 } ?: Analytics.CELLS_SERIES
        Panel(title = "Pakiet ${series}S") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Napięcie", t.voltage?.let { "${f1(it)} V" } ?: "–", Modifier.weight(1f))
                Stat("Śr. ogniwo", t.voltage?.let { String.format(Locale.US, "%.3f V", it / series) } ?: "–", Modifier.weight(1f))
                Stat("Temp.", t.batteryTempC?.let { "$it °C" } ?: "–", Modifier.weight(1f), tempColor(t.batteryTempC?.toDouble()))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Bateria (BMS)", "${t.batteryPct}%", Modifier.weight(1f), batteryColor(t.batteryPct))
                Stat(
                    "SoC z napięcia",
                    (st.restVoltage ?: t.voltage)?.let { "${Analytics.socFromVoltage(it, series)}%" } ?: "–",
                    Modifier.weight(1f),
                )
                Stat("Moc", t.powerW?.let { "${f0(it)} W" } ?: "–", Modifier.weight(1f))
            }
            Text(
                "SoC z napięcia liczony z krzywej Li-ion – porównanie z wskazaniem BMS pokazuje, czy licznik % nie kłamie.",
                color = Ui.Muted, fontSize = 11.sp,
            )
        }

        Panel(title = "Dane z BMS") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Cykle ładowania", t.bmsCycles?.toString() ?: "–", Modifier.weight(1f))
                Stat("Pojemność pełna", t.bmsFullCap?.let { "$it mAh" } ?: "–", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Pozostało", t.bmsRemainCap?.let { "$it mAh" } ?: "–", Modifier.weight(1f))
                Stat(
                    "Zdrowie (SoH)",
                    t.bmsFullCap?.let { fc -> if (fc > 0) "${f0(fc / 14000.0 * 100)}%" else null } ?: "–",
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("V (BMS)", t.bmsVoltage?.let { "${f1(it)} V" } ?: "–", Modifier.weight(1f))
                Stat("I (BMS)", t.bmsCurrent?.let { "${f1(it)} A" } ?: "–", Modifier.weight(1f))
                Stat("Czujniki temp.", if (t.bmsTemps.isEmpty()) "–" else t.bmsTemps.joinToString("/") + "°", Modifier.weight(1f))
            }
            Text("SoH = pojemność pełna względem nominalnych 14 Ah baterii W9.", color = Ui.Muted, fontSize = 11.sp)
        }

        Panel(title = "Kondycja pakietu") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Opór wewn.", st.resistanceMilliOhm?.let { "${f0(it)} mΩ" } ?: "–", Modifier.weight(1f), resistanceColor(st.resistanceMilliOhm))
                Stat("Spadek (sag)", st.sagV?.let { "${f1(it)} V" } ?: "–", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("V spoczynek", st.restVoltage?.let { "${f1(it)} V" } ?: "–", Modifier.weight(1f))
                Stat("V min (obc.)", st.minVoltage?.let { "${f1(it)} V" } ?: "–", Modifier.weight(1f))
                Stat("V max", st.maxVoltage?.let { "${f1(it)} V" } ?: "–", Modifier.weight(1f))
            }
            Text(
                "Opór wewnętrzny jest liczony z tego, jak napięcie spada przy przyspieszaniu. Pojawi się po kilku mocnych " +
                    "przyspieszeniach. Rosnący z miesiąca na miesiąc = starzejące się ogniwa. Dla 13S 14 Ah orientacyjnie: " +
                    "< 150 mΩ dobrze, 150–300 mΩ średnio, > 300 mΩ słabo.",
                color = Ui.Muted, fontSize = 11.sp,
            )
        }

        Panel(title = "Ogniwa") {
            if (cells.isEmpty()) {
                Text(
                    "Czekam na napięcia ogniw z BMS (zapytanie co ~3 s). Jeśli nic nie przychodzi, " +
                        "BMS tej hulajnogi nie udostępnia ogniw przez Bluetooth – surowe dane poniżej.",
                    color = Ui.Muted, fontSize = 12.sp,
                )
            } else {
                val mn = cells.min(); val mx = cells.max()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Min", String.format(Locale.US, "%.3f", mn), Modifier.weight(1f))
                    Stat("Max", String.format(Locale.US, "%.3f", mx), Modifier.weight(1f))
                    Stat("Różnica", "${f0((mx - mn) * 1000)} mV", Modifier.weight(1f), if (mx - mn > 0.05) Ui.Warn else Ui.Accent)
                }
                CellBars(cells, Modifier.fillMaxWidth().height(140.dp))
                Text(
                    "${cells.size} ogniw. Różnica > 50 mV po naładowaniu = pakiet niezbalansowany.",
                    color = Ui.Muted, fontSize = 11.sp,
                )
            }
        }

        Panel(title = "Surowe dane") {
            Text("0x20: " + Voltix.hex(t.rawMeter1).ifEmpty { "brak" }, color = Ui.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text("0x21: " + Voltix.hex(t.rawMeter2).ifEmpty { "brak" }, color = Ui.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text("0x64: " + Voltix.hex(t.rawBmsVoltage).ifEmpty { "brak" }, color = Ui.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text("0x65: " + Voltix.hex(t.rawBmsInfo).ifEmpty { "brak" }, color = Ui.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            PillButton("Odpytaj BMS", Modifier.fillMaxWidth(), color = Ui.Card2, textColor = Color.White) { s.queryInfo() }
        }
        Text("", Modifier.height(4.dp))
    }
}

@Composable
private fun CellBars(cells: List<Double>, modifier: Modifier) {
    Canvas(modifier) {
        val lo = 3.0; val hi = 4.25
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (cells.size - 1)) / cells.size
        val mn = cells.min(); val mx = cells.max()
        cells.forEachIndexed { i, v ->
            val f = ((v - lo) / (hi - lo)).coerceIn(0.03, 1.0).toFloat()
            val h = size.height * f
            val c = when {
                v == mn && mx - mn > 0.03 -> Ui.Warn
                v < 3.3 -> Ui.Danger
                else -> Ui.Accent
            }
            drawRect(Ui.Track, Offset(i * (w + gap), 0f), Size(w, size.height))
            drawRect(c, Offset(i * (w + gap), size.height - h), Size(w, h))
        }
    }
}

private fun tempColor(t: Double?): Color = when {
    t == null -> Color.White
    t > 55 -> Ui.Danger
    t > 45 -> Ui.Warn
    else -> Color.White
}

private fun resistanceColor(r: Double?): Color = when {
    r == null -> Color.White
    r > 300 -> Ui.Danger
    r > 150 -> Ui.Warn
    else -> Ui.Accent
}

// ------------------------------------------------------------------ test przyspieszenia

@Composable
private fun AccelTab() {
    val a = App.instance.scooter.analytics
    val state by a.accelState.collectAsStateWithLifecycle()
    val splits by a.accelSplits.collectAsStateWithLifecycle()
    val t by App.instance.scooter.telemetry.collectAsStateWithLifecycle()
    var target by remember { mutableIntStateOf(1) }
    val targets = listOf(20, 25, 30, 40)
    val fmt = remember { SimpleDateFormat("d.MM HH:mm", Locale.US) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(title = "Test przyspieszenia") {
            Segmented(targets.map { "0–$it" }, target) { target = it }
            Text(
                when (state) {
                    AccelState.IDLE -> "Zatrzymaj się, kliknij START i ruszaj pełnym gazem."
                    AccelState.ARMED -> "Gotowe – ruszaj! (${f0(t.speedKmh)} km/h)"
                    AccelState.RUNNING -> "Pomiar… ${f0(t.speedKmh)} / ${targets[target]} km/h"
                },
                color = if (state == AccelState.IDLE) Ui.Muted else Ui.Accent,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
            if (state == AccelState.IDLE) {
                PillButton("Start", Modifier.fillMaxWidth()) { a.armAccel(targets[target]) }
            } else {
                PillButton("Anuluj", Modifier.fillMaxWidth(), color = Ui.Danger, textColor = Color.White) { a.cancelAccel() }
            }
            Text(
                "Tylko na pustej, bezpiecznej drodze. Pomiar z prędkościomierza hulajnogi (interpolowany).",
                color = Ui.Muted, fontSize = 11.sp,
            )
        }
        Panel(title = "Wyniki") {
            if (splits.isEmpty()) Text("Brak pomiarów.", color = Ui.Muted)
            splits.forEach { r ->
                Row {
                    Text("0–${r.target} km/h", color = Color.White, modifier = Modifier.weight(1f))
                    Text(String.format(Locale.US, "%.2f s", r.seconds), color = Ui.Accent, fontWeight = FontWeight.Bold)
                    Text("   " + fmt.format(Date(r.time)), color = Ui.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}
