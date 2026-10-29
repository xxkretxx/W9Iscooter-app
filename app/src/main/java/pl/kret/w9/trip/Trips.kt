package pl.kret.w9.trip

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import pl.kret.w9.ble.ConnState
import pl.kret.w9.ble.ScooterManager
import java.io.File

data class TripPoint(val lat: Double, val lon: Double, val t: Long, val kmh: Double)

data class Trip(
    val id: Long,
    val start: Long,
    val end: Long,
    val distanceM: Double,
    val maxKmh: Double,
    val batteryStart: Int?,
    val batteryEnd: Int?,
    val points: List<TripPoint>,
) {
    val durationS: Long get() = (end - start) / 1000
    val avgKmh: Double get() = if (durationS > 0) distanceM / durationS * 3.6 else 0.0
    val batteryUsed: Int? get() = if (batteryStart != null && batteryEnd != null) batteryStart - batteryEnd else null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("start", start); put("end", end); put("dist", distanceM); put("max", maxKmh)
        batteryStart?.let { put("bs", it) }; batteryEnd?.let { put("be", it) }
        put("pts", JSONArray().apply {
            points.forEach { p -> put(JSONArray().apply { put(p.lat); put(p.lon); put(p.t); put(p.kmh) }) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Trip {
            val arr = o.optJSONArray("pts") ?: JSONArray()
            val pts = (0 until arr.length()).map {
                val a = arr.getJSONArray(it)
                TripPoint(a.getDouble(0), a.getDouble(1), a.getLong(2), a.getDouble(3))
            }
            return Trip(
                o.getLong("id"), o.getLong("start"), o.getLong("end"), o.getDouble("dist"), o.getDouble("max"),
                if (o.has("bs")) o.getInt("bs") else null, if (o.has("be")) o.getInt("be") else null, pts,
            )
        }
    }
}

class TripStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "trips").apply { mkdirs() }
    private val _trips = MutableStateFlow(load())
    val trips: StateFlow<List<Trip>> = _trips.asStateFlow()

    private fun load(): List<Trip> = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }
        .mapNotNull { runCatching { Trip.fromJson(JSONObject(it.readText())) }.getOrNull() }
        .sortedByDescending { it.start }

    fun save(t: Trip) {
        File(dir, "${t.id}.json").writeText(t.toJson().toString())
        _trips.value = load()
    }

    fun delete(t: Trip) {
        File(dir, "${t.id}.json").delete()
        _trips.value = load()
    }

    /** Ile km przejeżdżasz na 1% baterii (średnia z tras, gdzie zużyto ≥ 3%). */
    fun kmPerPercent(): Double? {
        val good = _trips.value.filter { (it.batteryUsed ?: 0) >= 3 && it.distanceM > 500 }
        if (good.isEmpty()) return null
        return good.sumOf { it.distanceM / 1000 } / good.sumOf { it.batteryUsed!! }
    }

    fun totalKm(): Double = _trips.value.sumOf { it.distanceM } / 1000
}

data class LiveTrip(
    val start: Long = 0,
    val distanceM: Double = 0.0,
    val maxKmh: Double = 0.0,
    val batteryStart: Int? = null,
    val points: List<TripPoint> = emptyList(),
    val gpsKmh: Double = 0.0,
)

@SuppressLint("MissingPermission")
class TripRecorder(
    private val ctx: Context,
    private val scooter: ScooterManager,
    private val store: TripStore,
) {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    private val _live = MutableStateFlow(LiveTrip())
    val live: StateFlow<LiveTrip> = _live.asStateFlow()
    private var last: Location? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        @Deprecated("old API")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun currentBattery(): Int? =
        if (scooter.state.value == ConnState.CONNECTED && scooter.telemetry.value.lastUpdate > 0)
            scooter.telemetry.value.batteryPct else null

    fun start() {
        if (_recording.value) return
        last = null
        _live.value = LiveTrip(start = System.currentTimeMillis(), batteryStart = currentBattery())
        _recording.value = true
        ContextCompat.startForegroundService(ctx, Intent(ctx, RideService::class.java))
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            scooter.addLog('•', "GPS niedostępny: ${e.message}")
        }
    }

    fun stop(save: Boolean = true) {
        if (!_recording.value) return
        lm.removeUpdates(listener)
        _recording.value = false
        ctx.stopService(Intent(ctx, RideService::class.java))
        val l = _live.value
        if (save && l.distanceM > 50) {
            store.save(
                Trip(l.start, l.start, System.currentTimeMillis(), l.distanceM, l.maxKmh, l.batteryStart, currentBattery(), l.points)
            )
        }
        _live.value = LiveTrip()
    }

    private fun onLocation(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 30f) return
        val prev = last
        val scooterKmh = if (scooter.state.value == ConnState.CONNECTED) scooter.telemetry.value.speedKmh else 0.0
        val gpsKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        val kmh = if (scooterKmh > 0.5) scooterKmh else gpsKmh
        var add = 0.0
        if (prev != null) {
            val d = prev.distanceTo(loc).toDouble()
            // odrzuć skoki GPS (> 80 km/h między punktami)
            val dt = (loc.time - prev.time) / 1000.0
            if (dt > 0 && d / dt < 22.0) add = d
        }
        last = loc
        val l = _live.value
        _live.value = l.copy(
            distanceM = l.distanceM + add,
            maxKmh = maxOf(l.maxKmh, kmh),
            batteryStart = l.batteryStart ?: currentBattery(),
            points = l.points + TripPoint(loc.latitude, loc.longitude, loc.time, kmh),
            gpsKmh = gpsKmh,
        )
    }
}
