package pl.kret.w9.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Sample(
    val t: Long,
    val speed: Double,
    val voltage: Double?,
    val current: Double?,
    val battery: Int,
) {
    val power: Double? get() = if (voltage != null && current != null) voltage * current else null
}

/** Statystyki sesji – rzeczy, których Voltix nie pokazuje. */
data class SessionStats(
    val energyWh: Double = 0.0,        // energia pobrana z baterii
    val regenWh: Double = 0.0,         // energia odzyskana (rekuperacja / KERS)
    val peakCurrentA: Double = 0.0,
    val peakRegenA: Double = 0.0,
    val peakPowerW: Double = 0.0,
    val maxVoltage: Double? = null,
    val minVoltage: Double? = null,    // najniższe napięcie pod obciążeniem (sag)
    val restVoltage: Double? = null,   // napięcie bez obciążenia (I < 1 A)
    val resistanceMilliOhm: Double? = null, // szacowany opór wewnętrzny pakietu
    val distanceKm: Double = 0.0,      // z prędkości hulajnogi
    val maxSpeed: Double = 0.0,
    val start: Long = System.currentTimeMillis(),
) {
    val whPerKm: Double? get() = if (distanceKm > 0.3) (energyWh - regenWh) / distanceKm else null
    val sagV: Double? get() = if (restVoltage != null && minVoltage != null) restVoltage - minVoltage else null
}

data class AccelResult(val target: Int, val seconds: Double, val time: Long)

enum class AccelState { IDLE, ARMED, RUNNING }

class Analytics {
    private val _samples = MutableStateFlow<List<Sample>>(emptyList())
    val samples: StateFlow<List<Sample>> = _samples.asStateFlow()

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val _accelState = MutableStateFlow(AccelState.IDLE)
    val accelState: StateFlow<AccelState> = _accelState.asStateFlow()
    private val _accelSplits = MutableStateFlow<List<AccelResult>>(emptyList())
    val accelSplits: StateFlow<List<AccelResult>> = _accelSplits.asStateFlow()
    var accelTarget = 25
    private var accelStart = 0L
    private var lastSpeed = 0.0
    private var lastSpeedT = 0L

    // regresja U = U0 - I·R z par (I, U)
    private val iuPairs = ArrayDeque<Pair<Double, Double>>()

    fun reset() {
        _samples.value = emptyList()
        _stats.value = SessionStats()
        iuPairs.clear()
    }

    fun onTelemetry(t: Telemetry) {
        val now = System.currentTimeMillis()
        val prev = _samples.value.lastOrNull()
        val s = Sample(now, t.speedKmh, t.voltage, t.currentA, t.batteryPct)
        _samples.value = (_samples.value + s).takeLast(MAX_SAMPLES)

        var st = _stats.value
        if (prev != null) {
            val dtH = (now - prev.t) / 3_600_000.0
            if (dtH in 0.0..(10.0 / 3600)) {
                st = st.copy(distanceKm = st.distanceKm + (prev.speed + s.speed) / 2 * dtH)
                val p = s.power
                if (p != null) {
                    st = if (p >= 0) st.copy(energyWh = st.energyWh + p * dtH)
                    else st.copy(regenWh = st.regenWh - p * dtH)
                }
            }
        }
        st = st.copy(maxSpeed = maxOf(st.maxSpeed, s.speed))
        val i = t.currentA
        val v = t.voltage
        if (i != null) {
            st = st.copy(
                peakCurrentA = maxOf(st.peakCurrentA, i),
                peakRegenA = maxOf(st.peakRegenA, -i),
            )
        }
        s.power?.let { st = st.copy(peakPowerW = maxOf(st.peakPowerW, it)) }
        if (v != null) {
            st = st.copy(
                maxVoltage = maxOf(st.maxVoltage ?: v, v),
                minVoltage = minOf(st.minVoltage ?: v, v),
            )
            if (i != null) {
                if (kotlin.math.abs(i) < 1.0) st = st.copy(restVoltage = v)
                iuPairs.addLast(i to v)
                if (iuPairs.size > 300) iuPairs.removeFirst()
                estimateResistance()?.let { st = st.copy(resistanceMilliOhm = it) }
            }
        }
        _stats.value = st
        accelStep(s.speed, now)
    }

    /** Opór wewnętrzny z regresji liniowej U(I); potrzebny rozrzut prądu ≥ 5 A. */
    private fun estimateResistance(): Double? {
        if (iuPairs.size < 20) return null
        val xs = iuPairs.map { it.first }
        val ys = iuPairs.map { it.second }
        if ((xs.max() - xs.min()) < 5.0) return null
        val mx = xs.average(); val my = ys.average()
        var num = 0.0; var den = 0.0
        for (k in xs.indices) { num += (xs[k] - mx) * (ys[k] - my); den += (xs[k] - mx) * (xs[k] - mx) }
        if (den == 0.0) return null
        val r = -num / den * 1000
        return if (r in 1.0..2000.0) r else null
    }

    // ---------- test przyspieszenia ----------
    fun armAccel(target: Int) {
        accelTarget = target
        _accelState.value = AccelState.ARMED
    }

    fun cancelAccel() { _accelState.value = AccelState.IDLE }

    private fun accelStep(speed: Double, now: Long) {
        when (_accelState.value) {
            AccelState.ARMED -> if (speed >= 1.0) {
                // interpolacja momentu przekroczenia 1 km/h
                accelStart = interpolate(lastSpeedT, lastSpeed, now, speed, 1.0)
                _accelState.value = AccelState.RUNNING
            }
            AccelState.RUNNING -> {
                if (speed >= accelTarget) {
                    val hit = interpolate(lastSpeedT, lastSpeed, now, speed, accelTarget.toDouble())
                    val res = AccelResult(accelTarget, (hit - accelStart) / 1000.0, now)
                    _accelSplits.value = (listOf(res) + _accelSplits.value).take(20)
                    _accelState.value = AccelState.IDLE
                } else if (speed < 0.5 || now - accelStart > 60_000) {
                    _accelState.value = AccelState.ARMED // zatrzymanie – spróbuj od nowa
                }
            }
            AccelState.IDLE -> {}
        }
        lastSpeed = speed
        lastSpeedT = now
    }

    private fun interpolate(t0: Long, v0: Double, t1: Long, v1: Double, target: Double): Long {
        if (t0 == 0L || v1 <= v0) return t1
        val f = ((target - v0) / (v1 - v0)).coerceIn(0.0, 1.0)
        return (t0 + (t1 - t0) * f).toLong()
    }

    companion object {
        const val MAX_SAMPLES = 600 // ~2–10 min zależnie od częstotliwości
        const val CELLS_SERIES = 13 // W9: 48 V = 13S Li-ion

        /** Szacowany SoC z napięcia spoczynkowego 13S (krzywa Li-ion NMC). */
        fun socFromVoltage(packV: Double, series: Int = CELLS_SERIES): Int {
            val c = packV / series
            val curve = listOf(
                3.00 to 0, 3.30 to 5, 3.45 to 10, 3.55 to 20, 3.62 to 30, 3.68 to 40,
                3.75 to 50, 3.82 to 60, 3.90 to 70, 3.98 to 80, 4.07 to 90, 4.20 to 100,
            )
            if (c <= curve.first().first) return 0
            if (c >= curve.last().first) return 100
            for (k in 1 until curve.size) {
                val (v1, p1) = curve[k]
                val (v0, p0) = curve[k - 1]
                if (c <= v1) return (p0 + (p1 - p0) * (c - v0) / (v1 - v0)).toInt()
            }
            return 100
        }
    }
}
