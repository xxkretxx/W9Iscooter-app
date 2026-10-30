package pl.kret.w9

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import pl.kret.w9.ble.ScooterManager
import pl.kret.w9.trip.TripRecorder
import pl.kret.w9.trip.TripStore

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var scooter: ScooterManager
    lateinit var trips: TripStore
    lateinit var recorder: TripRecorder
    lateinit var autoLight: AutoLight

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        scooter = ScooterManager(this, prefs)
        trips = TripStore(this)
        recorder = TripRecorder(this, scooter, trips)
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = cacheDir
        Configuration.getInstance().osmdroidTileCache = cacheDir.resolve("tiles")

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RIDE, "Jazda", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Alerty", NotificationManager.IMPORTANCE_HIGH))
        autoLight = AutoLight(this, scooter, prefs)
        autoLight.update()
        startAutoRecord()
        watchParking()
    }

    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var autoStarted = false
    private var stillSince = 0L

    /** Zapisuje miejsce parkowania, gdy połączenie z hulajnogą się kończy. */
    private fun watchParking() {
        appScope.launch {
            var wasConnected = false
            scooter.state.collect { st ->
                val c = st == pl.kret.w9.ble.ConnState.CONNECTED
                if (wasConnected && !c) saveParkingNow()
                wasConnected = c
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun saveParkingNow(): Boolean {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        val lm = getSystemService(android.location.LocationManager::class.java) ?: return false
        val loc = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time } ?: return false
        prefs.parkLat = loc.latitude
        prefs.parkLon = loc.longitude
        prefs.parkTime = System.currentTimeMillis()
        parkingVersion.value++
        return true
    }

    /** Zmienia się po każdym zapisie parkingu – ekran się odświeża. */
    val parkingVersion = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Auto-nagrywanie: start gdy jedziesz > 5 km/h, koniec po 3 min postoju. */
    private fun startAutoRecord() {
        appScope.launch {
            scooter.telemetry.collect { t ->
                if (!prefs.autoRecord) return@collect
                val connected = scooter.state.value == pl.kret.w9.ble.ConnState.CONNECTED
                val rec = recorder.recording.value
                val now = System.currentTimeMillis()
                if (!rec) autoStarted = false // zatrzymane ręcznie
                if (!rec && connected && t.speedKmh > 5.0 && !t.locked) {
                    recorder.start(); autoStarted = true; stillSince = 0L
                } else if (rec && autoStarted) {
                    if (t.speedKmh < 0.5) {
                        if (stillSince == 0L) stillSince = now
                        if (now - stillSince > 180_000) { recorder.stop(); autoStarted = false; stillSince = 0L }
                    } else stillSince = 0L
                }
            }
        }
    }

    companion object {
        const val CHANNEL_RIDE = "ride"
        const val CHANNEL_ALERTS = "alerts"
        lateinit var instance: App
            private set
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("w9", Context.MODE_PRIVATE)

    var lastAddress: String?
        get() = sp.getString("addr", null)
        set(v) = sp.edit().putString("addr", v).apply()
    var lastName: String?
        get() = sp.getString("name", null)
        set(v) = sp.edit().putString("name", v).apply()
    var autoReconnect: Boolean
        get() = sp.getBoolean("autoReconnect", true)
        set(v) = sp.edit().putBoolean("autoReconnect", v).apply()
    var pollMs: Int
        get() = sp.getInt("pollMs", 1000)
        set(v) = sp.edit().putInt("pollMs", v).apply()
    var speedAlarmKmh: Int
        get() = sp.getInt("speedAlarm", 0)
        set(v) = sp.edit().putInt("speedAlarm", v).apply()
    var lastGear: Int
        get() = sp.getInt("gear", 1)
        set(v) = sp.edit().putInt("gear", v).apply()
    var lightOnValue: Int
        get() = sp.getInt("lightOnValue", 1)
        set(v) = sp.edit().putInt("lightOnValue", v).apply()
    var mph: Boolean
        get() = sp.getBoolean("mph", false)
        set(v) = sp.edit().putBoolean("mph", v).apply()
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepScreenOn", true)
        set(v) = sp.edit().putBoolean("keepScreenOn", v).apply()
    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(v) = sp.edit().putString("pin", v).apply()
    var useGpsSpeed: Boolean
        get() = sp.getBoolean("gpsSpeed", false)
        set(v) = sp.edit().putBoolean("gpsSpeed", v).apply()
    var bmsPoll: Boolean
        get() = sp.getBoolean("bmsPoll", true)
        set(v) = sp.edit().putBoolean("bmsPoll", v).apply()
    var batteryAlerts: Boolean
        get() = sp.getBoolean("batteryAlerts", true)
        set(v) = sp.edit().putBoolean("batteryAlerts", v).apply()
    var autoLight: Boolean
        get() = sp.getBoolean("autoLight", false)
        set(v) = sp.edit().putBoolean("autoLight", v).apply()
    var kers: Int
        get() = sp.getInt("kers", 1)
        set(v) = sp.edit().putInt("kers", v).apply()
    var zeroStart: Boolean
        get() = sp.getBoolean("zeroStart", false)
        set(v) = sp.edit().putBoolean("zeroStart", v).apply()
    var customProfile: RideProfile
        get() = RideProfile(
            "Własny", sp.getInt("cp_gear", 2), sp.getInt("cp_limit", 25), sp.getInt("cp_kers", 2), sp.getBoolean("cp_zero", false),
        )
        set(v) = sp.edit().putInt("cp_gear", v.gear).putInt("cp_limit", v.limitKmh).putInt("cp_kers", v.kers)
            .putBoolean("cp_zero", v.zeroStart).apply()
    /** km (łączny przebieg) przy ostatnim wykonaniu czynności serwisowej. */
    fun serviceDoneAt(key: String): Double = sp.getFloat("svc_$key", 0f).toDouble()
    fun setServiceDone(key: String, km: Double) = sp.edit().putFloat("svc_$key", km.toFloat()).apply()

    /** Ostatnie miejsce parkowania (zapisane przy rozłączeniu z hulajnogą). */
    var parkLat: Double
        get() = sp.getString("parkLat", "")?.toDoubleOrNull() ?: Double.NaN
        set(v) = sp.edit().putString("parkLat", v.toString()).apply()
    var parkLon: Double
        get() = sp.getString("parkLon", "")?.toDoubleOrNull() ?: Double.NaN
        set(v) = sp.edit().putString("parkLon", v.toString()).apply()
    var parkTime: Long
        get() = sp.getLong("parkTime", 0L)
        set(v) = sp.edit().putLong("parkTime", v).apply()
    var priceKwh: Float
        get() = sp.getFloat("priceKwh", 1.10f)
        set(v) = sp.edit().putFloat("priceKwh", v).apply()

    var theftAlarm: Boolean
        get() = sp.getBoolean("theftAlarm", false)
        set(v) = sp.edit().putBoolean("theftAlarm", v).apply()
    var autoRecord: Boolean
        get() = sp.getBoolean("autoRecord", false)
        set(v) = sp.edit().putBoolean("autoRecord", v).apply()
    var hudMirror: Boolean
        get() = sp.getBoolean("hudMirror", true)
        set(v) = sp.edit().putBoolean("hudMirror", v).apply()
    var lockWithPin: Boolean
        get() = sp.getBoolean("lockWithPin", false)
        set(v) = sp.edit().putBoolean("lockWithPin", v).apply()
    var appLock: Boolean
        get() = sp.getBoolean("appLock", false)
        set(v) = sp.edit().putBoolean("appLock", v).apply()
    var ambientMode: Int
        get() = sp.getInt("ambMode", 0)
        set(v) = sp.edit().putInt("ambMode", v).apply()
    var ambientBrightness: Int
        get() = sp.getInt("ambBright", 100)
        set(v) = sp.edit().putInt("ambBright", v).apply()
    var ambientColors: List<Int>
        get() = (sp.getString("ambColors", "00E0A4") ?: "00E0A4").split(",").mapNotNull { it.toIntOrNull(16) }
        set(v) = sp.edit().putString("ambColors", v.joinToString(",") { "%06X".format(it and 0xFFFFFF) }).apply()

    var batteryWh: Int
        get() = sp.getInt("batteryWh", 672) // W9: 48 V × 14 Ah
        set(v) = sp.edit().putInt("batteryWh", v).apply()
}

data class RideProfile(val name: String, val gear: Int, val limitKmh: Int, val kers: Int, val zeroStart: Boolean) {
    companion object {
        /** Najdłuższy zasięg: ECO, max 20 km/h, ruszanie z odepchnięciem. */
        val LONG_RANGE = RideProfile("Long Range", 1, 20, 0, false)
        val PRESETS = listOf(
            RideProfile("Miasto", 2, 25, 0, true),
            RideProfile("TURBO", 3, 45, 0, true),
        )
    }
}

data class ServiceItem(val key: String, val name: String, val everyKm: Int)

val SERVICE_ITEMS = listOf(
    ServiceItem("tyres", "Ciśnienie w oponach", 100),
    ServiceItem("brakes", "Hamulce tarczowe (klocki, regulacja)", 300),
    ServiceItem("bolts", "Dokręcenie śrub i luz kierownicy", 300),
    ServiceItem("fold", "Mechanizm składania – smarowanie", 500),
    ServiceItem("suspension", "Zawieszenie – kontrola i smar", 800),
)

object Notifications {
    fun show(ctx: Context, id: Int, title: String, text: String) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val n = android.app.Notification.Builder(ctx, App.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}

/** Automatyczne światło na podstawie czujnika światła w telefonie (z histerezą). */
class AutoLight(ctx: Context, private val scooter: ScooterManager, private val prefs: Prefs) :
    android.hardware.SensorEventListener {
    private val sm = ctx.getSystemService(android.hardware.SensorManager::class.java)
    private val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT)
    private var registered = false
    private var darkSince = 0L
    private var brightSince = 0L
    private var lastSet: Boolean? = null
    val available: Boolean get() = sensor != null

    fun update() {
        if (prefs.autoLight && sensor != null && !registered) {
            sm?.registerListener(this, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
            registered = true
        } else if (!prefs.autoLight && registered) {
            sm?.unregisterListener(this)
            registered = false
            lastSet = null
        }
    }

    override fun onSensorChanged(e: android.hardware.SensorEvent) {
        if (scooter.state.value != pl.kret.w9.ble.ConnState.CONNECTED) { lastSet = null; return }
        val lux = e.values[0]
        val now = System.currentTimeMillis()
        if (lux < 15f) { brightSince = 0; if (darkSince == 0L) darkSince = now } else if (lux > 80f) { darkSince = 0; if (brightSince == 0L) brightSince = now }
        if (darkSince != 0L && now - darkSince > 4000 && lastSet != true) { scooter.setLight(true); lastSet = true }
        if (brightSince != 0L && now - brightSince > 10000 && lastSet != false) { scooter.setLight(false); lastSet = false }
    }

    override fun onAccuracyChanged(s: android.hardware.Sensor?, accuracy: Int) {}
}
