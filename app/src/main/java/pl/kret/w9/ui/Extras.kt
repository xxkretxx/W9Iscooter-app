package pl.kret.w9.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.kret.w9.App
import pl.kret.w9.trip.Trip
import pl.kret.w9.trip.TripPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Typowe zużycie W9 (Wh/km), gdy nie ma jeszcze własnych pomiarów. */
private const val DEFAULT_WH_PER_KM = 17.0

// ------------------------------------------------------------------ parking

/** Ostatnie miejsce parkowania: zapisywane samo przy rozłączeniu z hulajnogą. */
@Composable
fun ParkingPanel() {
    val app = App.instance
    val ctx = LocalContext.current
    val ver by app.parkingVersion.collectAsStateWithLifecycle()
    val lat = remember(ver) { app.prefs.parkLat }
    val lon = remember(ver) { app.prefs.parkLon }
    val time = remember(ver) { app.prefs.parkTime }
    val has = !lat.isNaN() && !lon.isNaN()

    Panel(title = "Gdzie zaparkowałem") {
        if (has) {
            Text(
                "Zapisano " + SimpleDateFormat("d MMM, HH:mm", Locale("pl")).format(Date(time)),
                color = Color.White, fontSize = 15.sp,
            )
            RouteMap(
                listOf(TripPoint(lat, lon, time, 0.0)),
                Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Prowadź pieszo", Modifier.weight(1f)) {
                    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=w"))
                    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(Hulajnoga)"))
                    try { ctx.startActivity(nav) } catch (_: Exception) {
                        try { ctx.startActivity(geo) } catch (_: Exception) {}
                    }
                }
                PillButton("Zapisz tu", Modifier.weight(1f), color = Ui.Card2, textColor = Color.White) { app.saveParkingNow() }
            }
        } else {
            Text(
                "Miejsce zapisze się samo, gdy rozłączysz się z hulajnogą (np. wyłączysz ją po jeździe).",
                color = Ui.Muted, fontSize = 13.sp,
            )
            PillButton("Zapisz bieżące miejsce", Modifier.fillMaxWidth(), color = Ui.Card2, textColor = Color.White) { app.saveParkingNow() }
        }
    }
}

// ------------------------------------------------------------------ rekordy

@Composable
fun RecordsPanel(trips: List<Trip>) {
    if (trips.isEmpty()) return
    val top = trips.maxByOrNull { it.maxKmh }
    val longest = trips.maxByOrNull { it.distanceM }
    val longestTime = trips.maxByOrNull { it.durationS }
    val totalS = trips.sumOf { it.durationS }
    val best = trips.mapNotNull { t -> ecoScore(t)?.let { t to it } }.maxByOrNull { it.second }
    val fmt = SimpleDateFormat("d.MM.yy", Locale.US)

    Panel(title = "Rekordy") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Prędkość max", top?.let { "${f0(Ui.speed(it.maxKmh))} ${Ui.speedUnit}" } ?: "–", Modifier.weight(1f), Ui.Danger)
            Stat("Najdłuższa", longest?.let { "${f1(Ui.dist(it.distanceM / 1000))} ${Ui.distUnit}" } ?: "–", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Najdłużej", longestTime?.let { duration(it.durationS) } ?: "–", Modifier.weight(1f))
            Stat("Łącznie w trasie", duration(totalS), Modifier.weight(1f))
        }
        best?.let { (t, sc) ->
            Text(
                "Najbardziej oszczędna jazda: ${fmt.format(Date(t.start))} · ${"★".repeat((sc + 10) / 20).padEnd(5, '☆')}",
                color = Ui.Muted, fontSize = 13.sp,
            )
        }
    }
}

// ------------------------------------------------------------------ czy dojadę?

@Composable
fun RangeCheckPanel() {
    val app = App.instance
    val t by app.scooter.telemetry.collectAsStateWithLifecycle()
    val st by app.scooter.analytics.stats.collectAsStateWithLifecycle()
    var km by remember { mutableFloatStateOf(10f) }

    // zużycie: z bieżącej sesji (dokładne), potem z historii tras, na końcu typowe dla W9
    val whPerKm = st.whPerKm?.takeIf { it in 5.0..60.0 }
        ?: app.trips.kmPerPercent()?.let { app.prefs.batteryWh / 100.0 / it }
        ?: DEFAULT_WH_PER_KM
    val haveWh = t.batteryPct / 100.0 * app.prefs.batteryWh
    val rangeKm = haveWh / whPerKm
    val needPct = km * whPerKm / app.prefs.batteryWh * 100
    val leftPct = t.batteryPct - needPct
    val ok = leftPct >= 5

    Panel(title = "Czy dojadę?") {
        Text("Dystans: ${f0(Ui.dist(km.toDouble()))} ${Ui.distUnit}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Slider(
            value = km, onValueChange = { km = it }, valueRange = 1f..60f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Ui.Accent, inactiveTrackColor = Ui.Track),
        )
        if (t.lastUpdate == 0L) {
            Text("Połącz się z hulajnogą, żeby policzyć na podstawie baterii.", color = Ui.Muted, fontSize = 13.sp)
        } else {
            Text(
                if (ok) "✓ Dojedziesz. Zostanie ok. ${f0(leftPct)}% baterii."
                else if (leftPct > 0) "⚠ Na styk – zostanie tylko ${f0(leftPct)}%. Jedź w trybie Long Range."
                else "✕ Nie dojedziesz – zabraknie ok. ${f0(Ui.dist(km - rangeKm))} ${Ui.distUnit}.",
                color = if (ok) Ui.Accent else if (leftPct > 0) Ui.Warn else Ui.Danger,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Zasięg teraz: ~${f0(Ui.dist(rangeKm))} ${Ui.distUnit} przy ${f0(whPerKm)} Wh/km" +
                    if (st.whPerKm != null) " (twoje bieżące zużycie)" else "",
                color = Ui.Muted, fontSize = 12.sp,
            )
        }
    }
}

// ------------------------------------------------------------------ koszt

@Composable
fun CostPanel(totalKm: Double) {
    if (totalKm < 0.1) return
    val app = App.instance
    val st by app.scooter.analytics.stats.collectAsStateWithLifecycle()
    var price by remember { mutableFloatStateOf(app.prefs.priceKwh) }
    val whPerKm = st.whPerKm?.takeIf { it in 5.0..60.0 } ?: DEFAULT_WH_PER_KM
    // ładowarka ma ok. 85% sprawności – z gniazdka bierzesz więcej niż trafia do baterii
    val kwh = totalKm * whPerKm / 1000 / 0.85
    val cost = kwh * price
    val car = totalKm * 7.0 / 100 * 6.2   // auto: 7 l/100 km × 6,20 zł/l
    val co2 = totalKm * 0.12              // auto: ok. 120 g CO₂/km

    Panel(title = "Koszt jazdy") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Prąd", String.format(Locale.US, "%.2f zł", cost), Modifier.weight(1f), Ui.Accent)
            Stat("Autem by było", "${f0(car)} zł", Modifier.weight(1f), Ui.Danger)
            Stat("CO₂ mniej", "${f1(co2)} kg", Modifier.weight(1f))
        }
        Text(
            "Za ${f0(Ui.dist(totalKm))} ${Ui.distUnit} z historii tras · ${String.format(Locale.US, "%.1f", kwh)} kWh z gniazdka",
            color = Ui.Muted, fontSize = 12.sp,
        )
        Text("Cena prądu: ${String.format(Locale.US, "%.2f", price)} zł/kWh", color = Color.White, fontSize = 15.sp)
        Slider(
            value = price, onValueChange = { price = it }, valueRange = 0.3f..2.0f,
            onValueChangeFinished = { app.prefs.priceKwh = price },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Ui.Accent, inactiveTrackColor = Ui.Track),
        )
    }
}
