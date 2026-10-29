package pl.kret.w9.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.kret.w9.App
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Paleta w stylu iOS (tryb ciemny). */
object Ui {
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF1C1C1E)
    val Card2 = Color(0xFF2C2C2E)
    val Track = Color(0xFF3A3A3C)
    val Separator = Color(0xFF38383A)
    val Accent = Color(0xFF30D158)   // systemGreen
    val Blue = Color(0xFF0A84FF)     // systemBlue
    val Teal = Color(0xFF64D2FF)
    val Yellow = Color(0xFFFFD60A)
    val Warn = Color(0xFFFF9F0A)     // systemOrange
    val Danger = Color(0xFFFF453A)   // systemRed
    val Purple = Color(0xFFBF5AF2)
    val Muted = Color(0xFF8E8E93)    // secondaryLabel

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

private val iosSpring = spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)

@Composable
fun W9Theme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ui.Accent,
            onPrimary = Color.Black,
            secondary = Ui.Blue,
            background = Ui.Bg,
            surface = Ui.Card,
            surfaceVariant = Ui.Card2,
            surfaceContainerHigh = Ui.Card,
            outline = Ui.Separator,
            onSurface = Color.White,
            onSurfaceVariant = Ui.Muted,
        ),
        typography = base.copy(
            bodyLarge = base.bodyLarge.copy(letterSpacing = (-0.2).sp),
            bodyMedium = base.bodyMedium.copy(letterSpacing = (-0.1).sp),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        ),
        content = content,
    )
}

/** Krótka wibracja jak w iOS (Taptic). */
@Composable
fun rememberHaptic(): () -> Unit {
    val view = LocalView.current
    val f: () -> Unit = remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); Unit } }
    return f
}

/** Sprężyste „wciśnięcie” jak w iOS. */
@Composable
fun Modifier.bounceClick(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, iosSpring, label = "press")
    val haptic = rememberHaptic()
    return this
        .scale(s)
        .clickable(interactionSource = source, indication = null, enabled = enabled) { haptic(); onClick() }
}

/** Animacja pojawienia się (fade + przesunięcie), opcjonalnie z opóźnieniem. */
@Composable
fun Appear(delayMs: Int = 0, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (delayMs > 0) delay(delayMs.toLong()); shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(380, easing = FastOutSlowInEasing)) +
            slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)) { it / 5 },
    ) { content() }
}

/** Duży tytuł ekranu jak w aplikacjach iOS. */
@Composable
fun LargeTitle(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) Text(subtitle.uppercase(), color = Ui.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Text(title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
        }
        trailing?.invoke()
    }
}

/** Grupa w stylu „inset grouped” z iOS: nagłówek nad kartą. */
@Composable
fun Panel(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Appear {
        Column(modifier.fillMaxWidth()) {
            if (title != null) {
                Text(
                    title.uppercase(), color = Ui.Muted, fontSize = 13.sp, letterSpacing = 0.3.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, end = 16.dp),
                )
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Card)
                    .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** Kafelek z wartością – liczby zmieniają się z animacją „przewijania”. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Ui.Card2).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = Ui.Muted, fontSize = 12.sp, maxLines = 1)
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                    (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(180)))
            },
            label = "stat",
        ) { v ->
            Text(
                v, color = color, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
        }
    }
}

/** Przełącznik jak w iOS: zielony tor, biała gałka ze sprężyną. */
@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) Ui.Accent else Color(0xFF39393D), tween(220), label = "track")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.65f, stiffness = 500f), label = "thumb")
    val haptic = rememberHaptic()
    Box(
        Modifier.size(width = 51.dp, height = 31.dp).clip(CircleShape).background(track)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { haptic(); onChange(!checked) },
    ) {
        Box(
            Modifier.offset(x = x, y = 2.dp).size(27.dp).clip(CircleShape).background(Color.White),
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = Color.White, fontSize = 17.sp, letterSpacing = (-0.3).sp)
            if (sub != null) Text(sub, color = Ui.Muted, fontSize = 13.sp)
        }
        IosSwitch(checked, onChange)
    }
}

/** Kontrolka segmentowa iOS z przesuwaną „pigułką”. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val haptic = rememberHaptic()
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2C2C2E)).padding(2.dp),
    ) {
        val n = options.size.coerceAtLeast(1)
        val w: Dp = maxWidth / n
        val x by animateDpAsState(w * selected.coerceAtLeast(0), spring(dampingRatio = 0.8f, stiffness = 400f), label = "seg")
        val a by animateFloatAsState(if (selected in options.indices) 1f else 0f, label = "segA")
        Box(
            Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF636366).copy(alpha = a)),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (i != selected) haptic()
                            onSelect(i)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, color = Color.White, maxLines = 1,
                        fontSize = 13.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Duży przycisk w kształcie pigułki. */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ui.Accent,
    textColor: Color = Color.Black,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier.height(50.dp).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) color else Ui.Card2)
            .bounceClick(enabled) { onClick() }
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(8.dp)) }
        Text(text, color = if (enabled) textColor else Ui.Muted, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Pulsująca kropka statusu. */
@Composable
fun PulseDot(color: Color, pulsing: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(1f, 2.4f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "s")
    val a by t.animateFloat(0.55f, 0f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "a")
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (pulsing) Box(Modifier.size(9.dp).scale(s).clip(CircleShape).background(color.copy(alpha = a)))
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
    }
}

/** Pierścień postępu (bateria). */
@Composable
fun Ring(progress: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 10.dp) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), spring(dampingRatio = 0.9f, stiffness = 60f), label = "ring")
    Canvas(modifier) {
        val sw = stroke.toPx()
        val tl = Offset(sw / 2, sw / 2)
        val sz = Size(size.width - sw, size.height - sw)
        drawArc(color.copy(alpha = 0.18f), -90f, 360f, false, topLeft = tl, size = sz, style = Stroke(sw))
        drawArc(color, -90f, 360f * p, false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** Prędkościomierz: gradient zielony→żółty→czerwony, poświata, podziałka i animowana wskazówka. */
@Composable
fun Speedometer(kmh: Double, maxKmh: Double, modifier: Modifier = Modifier, label: String? = null) {
    val shown = Ui.speed(kmh).toFloat()
    val max = Ui.speed(maxKmh).toFloat()
    val anim by animateFloatAsState(shown, spring(dampingRatio = 0.85f, stiffness = 120f), label = "speed")
    val brush = remember {
        Brush.sweepGradient(
            0f to Color(0xFFFF7A1A),
            0.125f to Ui.Danger,
            0.30f to Ui.Danger,
            0.375f to Ui.Accent,
            0.55f to Color(0xFF7CE65C),
            0.75f to Ui.Yellow,
            1f to Color(0xFFFF7A1A),
        )
    }
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val stroke = 16.dp.toPx()
            val tl = Offset(stroke * 1.4f, stroke * 1.4f)
            val sz = Size(size.width - stroke * 2.8f, size.height - stroke * 2.8f)
            val frac = (anim / max).coerceIn(0f, 1f)
            // tor
            drawArc(Color(0xFF1C1C1E), 135f, 270f, false, topLeft = tl, size = sz, style = Stroke(stroke, cap = StrokeCap.Round))
            // podziałka
            val r = size.minDimension / 2
            val c = Offset(size.width / 2, size.height / 2)
            for (k in 0..27) {
                val ang = Math.toRadians((135 + k * 10).toDouble())
                val major = k % 3 == 0
                val r1 = r - (if (major) 2.dp.toPx() else 4.dp.toPx())
                val r0 = r - 9.dp.toPx()
                drawLine(
                    if (k / 27f <= frac) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.18f),
                    Offset(c.x + (r0 * cos(ang)).toFloat(), c.y + (r0 * sin(ang)).toFloat()),
                    Offset(c.x + (r1 * cos(ang)).toFloat(), c.y + (r1 * sin(ang)).toFloat()),
                    strokeWidth = if (major) 2.5f else 1.5f, cap = StrokeCap.Round,
                )
            }
            if (frac > 0.004f) {
                // poświata
                drawArc(brush, 135f, 270f * frac, false, topLeft = tl, size = sz, alpha = 0.22f, style = Stroke(stroke * 2.2f, cap = StrokeCap.Round))
                drawArc(brush, 135f, 270f * frac, false, topLeft = tl, size = sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // wskazówka – biała kropka na końcu łuku
            val ra = sz.width / 2
            val ang = Math.toRadians((135 + 270 * frac).toDouble())
            val p = Offset(c.x + (ra * cos(ang)).toFloat(), c.y + (ra * sin(ang)).toFloat())
            drawCircle(Color.White.copy(alpha = 0.25f), radius = stroke * 0.95f, center = p)
            drawCircle(Color.White, radius = stroke * 0.42f, center = p)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                f0(anim.toDouble()), color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light,
                letterSpacing = (-4).sp, style = TextStyle(fontFeatureSettings = "tnum"),
            )
            Text(Ui.speedUnit, color = Ui.Muted, fontSize = 17.sp, modifier = Modifier.offset(y = (-8).dp))
            if (label != null) {
                Text(
                    label.uppercase(), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 4.dp).clip(CircleShape).background(Ui.Accent).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}
