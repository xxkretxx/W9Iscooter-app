package pl.kret.w9

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Syrena alarmu antykradzieżowego grana z głośnika telefonu.
 * Ton przeciąga się w górę i w dół (jak alarm samochodowy). Gra z głośnika na maksa, w pętli, aż ją zatrzymasz.
 */
object Siren {
    private const val RATE = 44100
    private var track: AudioTrack? = null

    private val pcm: ShortArray by lazy {
        // 1 s: ton 600 → 1200 Hz w górę i z powrotem
        val n = RATE
        ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val sweep = if (t < 0.5) t / 0.5 else (1 - t) / 0.5
            val f = 600.0 + 600.0 * sweep
            (sin(2 * PI * f * t) * Short.MAX_VALUE * 0.9).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    @Synchronized
    fun start() {
        if (track != null) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        t.play()
        track = t
    }

    @Synchronized
    fun stop() {
        track?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        track = null
    }

    val playing: Boolean get() = track != null

    /** Podgłaśnia multimedia do maksimum, żeby alarm było słychać. */
    fun maxVolume(am: AudioManager?) {
        am ?: return
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
    }
}
