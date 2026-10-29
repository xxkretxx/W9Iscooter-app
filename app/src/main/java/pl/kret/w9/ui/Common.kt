package pl.kret.w9.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.kret.w9.App
import java.util.Locale

object Ui {
    val Bg = Color(0xFF0B0F14)
    val Card = Color(0xFF151B23)
    val Track = Color(0xFF243040)
    val Accent = Color(0xFF00E0A4)
    val Warn = Color(0xFFFFB020)
    val Danger = Color(0xFFFF4D5E)
    val Muted = Color(0xFF8A97A8)

    /** Wyświetlanie w mph (ustawienie aplikacji). */
    val mph = mutableStateOf(App.instance.prefs.mph)

    fun speed(kmh: Double): Double = if (mph.value) kmh * 0.621371 else kmh
    fun dist(km: Double): Double = if (mph.value) km * 0.621371 else km
    val speedUnit: String get() = if (mph.value) "mph" else "km/h"
    val distUnit: String get() = if (mph.value) "mi" else "km"
}

fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
fun duration(s: Long): String = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

@Composable
fun W9Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ui.Accent,
            onPrimary = Color.Black,
            secondary = Ui.Accent,
            background = Ui.Bg,
            surface = Ui.Card,
            surfaceVariant = Ui.Track,
        ),
        content = content,
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Ui.Card),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, color = Ui.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Ui.Card),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, color = Ui.Muted, fontSize = 12.sp)
            Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 16.sp)
            if (sub != null) Text(sub, color = Ui.Muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Ui.Accent, checkedThumbColor = Color.Black),
        )
    }
}

@Composable
fun Speedometer(kmh: Double, maxKmh: Double, modifier: Modifier = Modifier) {
    val shown = Ui.speed(kmh).toFloat()
    val max = Ui.speed(maxKmh).toFloat()
    val anim by animateFloatAsState(targetValue = shown, label = "speed")
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val stroke = 20.dp.toPx()
            val tl = Offset(stroke / 2, stroke / 2)
            val sz = Size(size.width - stroke, size.height - stroke)
            drawArc(Ui.Track, 135f, 270f, false, topLeft = tl, size = sz, style = Stroke(stroke, cap = StrokeCap.Round))
            val frac = (anim / max).coerceIn(0f, 1f)
            if (frac > 0.003f) {
                drawArc(
                    lerp(Ui.Accent, Ui.Danger, frac), 135f, 270f * frac, false,
                    topLeft = tl, size = sz, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(f0(anim.toDouble()), color = Color.White, fontSize = 84.sp, fontWeight = FontWeight.Black)
            Text(Ui.speedUnit, color = Ui.Muted, fontSize = 18.sp)
        }
    }
}
