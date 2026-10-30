package pl.kret.w9.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hulajnoga terenowa (sylwetka w stylu W9: grube opony, podwójne zawieszenie, wyświetlacz na kierownicy)
 * rysowana wektorowo, z efektem 3D: obrót w przestrzeni, unoszenie, cień, kręcące się koła.
 * Światła odzwierciedlają stan z hulajnogi: przednie, stop, kierunkowskazy.
 */
@Composable
fun Scooter3D(
    headlight: Boolean,
    braking: Boolean,
    leftTurn: Boolean,
    rightTurn: Boolean,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    val tr = rememberInfiniteTransition(label = "scooter")
    val sway by tr.animateFloat(-14f, 14f, infiniteRepeatable(tween(4200), RepeatMode.Reverse), label = "sway")
    val float by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "float")
    val wheel by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "wheel")
    val blink by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "blink")
    val blinkOn = blink < 0.5f
    val glow by tr.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "glow")

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.55f), contentAlignment = Alignment.Center) {
            // cień na podłodze – „oddycha” razem z unoszeniem
            Canvas(Modifier.fillMaxWidth().aspectRatio(1.55f)) {
                val s = size.width / 400f
                val w = (300f - 30f * float) * s
                drawOval(
                    Brush.radialGradient(
                        listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent),
                        center = Offset(size.width / 2, 236f * s), radius = w / 2,
                    ),
                    topLeft = Offset(size.width / 2 - w / 2, 226f * s), size = Size(w, 22f * s),
                )
                // poświata pod hulajnogą
                if (connected) {
                    drawOval(
                        Brush.radialGradient(
                            listOf(Ui.Accent.copy(alpha = 0.22f * glow), Color.Transparent),
                            center = Offset(size.width / 2, 225f * s), radius = 170f * s,
                        ),
                        topLeft = Offset(size.width / 2 - 170f * s, 195f * s), size = Size(340f * s, 60f * s),
                    )
                }
            }
            Canvas(
                Modifier.fillMaxWidth().aspectRatio(1.55f).graphicsLayer {
                    rotationY = sway
                    rotationX = 4f
                    translationY = -10f * float * density
                    cameraDistance = 14f * density
                },
            ) {
                val s = size.width / 400f
                withTransform({ scale(s, s, pivot = Offset.Zero) }) {
                    drawScooter(headlight, braking, leftTurn && blinkOn, rightTurn && blinkOn, wheel, connected, glow)
                }
            }
        }
        // kierunkowskazy i stop – pod modelem
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("◀", color = if (leftTurn && blinkOn) Ui.Warn else Ui.Track, fontSize = 22.sp)
            Text(
                if (braking) "STOP" else if (connected) "GOTOWA" else "OFFLINE",
                color = if (braking) Ui.Danger else if (connected) Ui.Accent else Ui.Muted,
                fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            )
            Text("▶", color = if (rightTurn && blinkOn) Ui.Warn else Ui.Track, fontSize = 22.sp)
        }
    }
}

/** Rysunek w układzie 400 × 258. Hulajnoga patrzy w prawo. */
private fun DrawScope.drawScooter(
    headlight: Boolean, braking: Boolean, left: Boolean, right: Boolean, wheelAngle: Float, connected: Boolean, glow: Float,
) {
    val rear = Offset(88f, 190f)
    val front = Offset(318f, 190f)
    val metal = Brush.linearGradient(listOf(Color(0xFF5A5A5F), Color(0xFF2C2C2E)), start = Offset(0f, 0f), end = Offset(0f, 260f))
    val metalLight = Color(0xFF8E8E93)

    // ---------- snop światła ----------
    if (headlight) {
        val beam = Path().apply {
            moveTo(312f, 104f); lineTo(400f, 70f); lineTo(400f, 150f); close()
        }
        drawPath(beam, Brush.horizontalGradient(listOf(Color(0x99FFF3B0), Color.Transparent), startX = 312f, endX = 400f))
    }

    // ---------- tylne zawieszenie i wahacz ----------
    drawLine(metal, Offset(118f, 172f), rear, strokeWidth = 11f, cap = StrokeCap.Round)
    drawSpring(Offset(132f, 160f), Offset(104f, 140f))

    // ---------- koła ----------
    drawWheel(rear, wheelAngle)
    drawWheel(front, wheelAngle)

    // błotniki
    drawArc(Color(0xFF2C2C2E), 195f, 120f, false, topLeft = Offset(rear.x - 46f, rear.y - 46f), size = Size(92f, 92f), style = Stroke(8f, cap = StrokeCap.Round))
    drawArc(Color(0xFF2C2C2E), 215f, 115f, false, topLeft = Offset(front.x - 46f, front.y - 46f), size = Size(92f, 92f), style = Stroke(8f, cap = StrokeCap.Round))

    // ---------- światło tylne / stop ----------
    val tail = if (braking) Ui.Danger else Ui.Danger.copy(alpha = 0.55f)
    if (braking) drawCircle(Ui.Danger.copy(alpha = 0.35f), 16f, Offset(80f, 146f))
    drawRoundRect(tail, topLeft = Offset(72f, 141f), size = Size(16f, 9f), cornerRadius = CornerRadius(4f))

    // ---------- deck ----------
    val deckTop = 150f
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF48484A), Color(0xFF1C1C1E)), startY = deckTop, endY = deckTop + 24f),
        topLeft = Offset(100f, deckTop), size = Size(178f, 22f), cornerRadius = CornerRadius(10f),
    )
    // gumowa nakładka
    drawRoundRect(Color(0xFF111113), topLeft = Offset(112f, deckTop - 4f), size = Size(150f, 7f), cornerRadius = CornerRadius(4f))
    // podświetlenie pod deckiem (W9 ma światła pod platformą)
    if (connected) {
        drawLine(Ui.Accent.copy(alpha = 0.35f * glow), Offset(118f, deckTop + 26f), Offset(262f, deckTop + 26f), strokeWidth = 12f, cap = StrokeCap.Round)
        drawLine(Ui.Accent.copy(alpha = 0.9f), Offset(122f, deckTop + 24f), Offset(258f, deckTop + 24f), strokeWidth = 3f, cap = StrokeCap.Round)
    }

    // ---------- przód: widelec z amortyzatorem, kolumna, kierownica ----------
    drawLine(metal, front, Offset(300f, 120f), strokeWidth = 12f, cap = StrokeCap.Round)
    drawSpring(Offset(312f, 160f), Offset(304f, 130f))
    drawLine(metal, Offset(270f, 160f), Offset(300f, 122f), strokeWidth = 12f, cap = StrokeCap.Round) // szyjka
    drawLine(metal, Offset(300f, 122f), Offset(283f, 34f), strokeWidth = 10f, cap = StrokeCap.Round) // kolumna
    drawLine(metalLight, Offset(299f, 116f), Offset(284f, 40f), strokeWidth = 2f, cap = StrokeCap.Round) // odblask

    // kierownica
    drawLine(Color(0xFF3A3A3C), Offset(256f, 32f), Offset(310f, 32f), strokeWidth = 7f, cap = StrokeCap.Round)
    drawLine(Color(0xFF111113), Offset(256f, 32f), Offset(266f, 32f), strokeWidth = 9f, cap = StrokeCap.Round)
    drawLine(Color(0xFF111113), Offset(300f, 32f), Offset(310f, 32f), strokeWidth = 9f, cap = StrokeCap.Round)
    // kierunkowskazy na końcach kierownicy
    drawCircle(if (left) Ui.Warn else Color(0xFF3A3A3C), 5f, Offset(252f, 32f))
    drawCircle(if (right) Ui.Warn else Color(0xFF3A3A3C), 5f, Offset(314f, 32f))
    if (left) drawCircle(Ui.Warn.copy(alpha = 0.35f), 13f, Offset(252f, 32f))
    if (right) drawCircle(Ui.Warn.copy(alpha = 0.35f), 13f, Offset(314f, 32f))

    // wyświetlacz
    drawRoundRect(Color(0xFF1C1C1E), topLeft = Offset(272f, 22f), size = Size(24f, 14f), cornerRadius = CornerRadius(3f))
    if (connected) drawRoundRect(Ui.Accent.copy(alpha = 0.85f), topLeft = Offset(275f, 25f), size = Size(18f, 8f), cornerRadius = CornerRadius(2f))

    // reflektor
    if (headlight) drawCircle(Color(0x66FFF3B0), 16f, Offset(308f, 104f))
    drawCircle(if (headlight) Color(0xFFFFF7CC) else Color(0xFF48484A), 6.5f, Offset(308f, 104f))
}

/** Gruba opona terenowa z bieżnikiem, felga i szprychy obracające się o [angle]. */
private fun DrawScope.drawWheel(c: Offset, angle: Float) {
    // opona
    drawCircle(Color(0xFF0E0E10), 36f, c)
    // bieżnik (kostki) – obraca się razem z kołem
    rotate(angle, c) {
        drawCircle(
            Color(0xFF2C2C2E), 37f, c,
            style = Stroke(5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f))),
        )
    }
    // felga
    drawCircle(Brush.radialGradient(listOf(Color(0xFF636366), Color(0xFF2C2C2E)), center = c, radius = 22f), 22f, c)
    drawCircle(Color(0xFF1C1C1E), 17f, c)
    // szprychy
    for (k in 0 until 5) {
        val a = Math.toRadians((angle + k * 72f).toDouble())
        drawLine(
            Color(0xFF8E8E93),
            Offset(c.x + (5f * cos(a)).toFloat(), c.y + (5f * sin(a)).toFloat()),
            Offset(c.x + (17f * cos(a)).toFloat(), c.y + (17f * sin(a)).toFloat()),
            strokeWidth = 3.5f, cap = StrokeCap.Round,
        )
    }
    // tarcza hamulca + piasta
    drawCircle(Color(0xFF8E8E93), 9f, c, style = Stroke(1.5f))
    drawCircle(Ui.Accent, 4.5f, c)
}

/** Sprężyna amortyzatora (zygzak) między dwoma punktami. */
private fun DrawScope.drawSpring(a: Offset, b: Offset) {
    val n = 7
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    val nx = -dy / len * 5f
    val ny = dx / len * 5f
    val p = Path().apply {
        moveTo(a.x, a.y)
        for (i in 1 until n) {
            val t = i.toFloat() / n
            val side = if (i % 2 == 0) 1 else -1
            lineTo(a.x + dx * t + nx * side, a.y + dy * t + ny * side)
        }
        lineTo(b.x, b.y)
    }
    drawLine(Color(0xFF3A3A3C), a, b, strokeWidth = 9f, cap = StrokeCap.Round)
    drawPath(p, Ui.Warn, style = Stroke(2.5f))
}
