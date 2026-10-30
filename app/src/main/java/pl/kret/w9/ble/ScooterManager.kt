package pl.kret.w9.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.kret.w9.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

data class ScanItem(val address: String, val name: String, val rssi: Int, val voltix: Boolean)

data class LogLine(val time: Long, val dir: Char, val text: String) {
    val line: String get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(time)) + " $dir $text"
}

@SuppressLint("MissingPermission")
class ScooterManager(private val ctx: Context, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val btManager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = btManager?.adapter

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private val _scan = MutableStateFlow<List<ScanItem>>(emptyList())
    val scanResults: StateFlow<List<ScanItem>> = _scan.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _alarm = MutableStateFlow(false)
    /** Czy wyje alarm antykradzieżowy. */
    val alarm: StateFlow<Boolean> = _alarm.asStateFlow()

    /** Ustawienia zapamiętane lokalnie (hulajnoga nie zawsze je raportuje). */
    private val _gear = MutableStateFlow(prefs.lastGear)
    val gear: StateFlow<Int> = _gear.asStateFlow()

    val analytics = Analytics()

    /** Szybkie odpytywanie (wykresy / test przyspieszenia). */
    @Volatile var fastPoll = false

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val rx = ArrayList<Byte>()
    private val writeQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var writeAck: Channel<Unit> = Channel(Channel.CONFLATED)
    private var sessionJobs = ArrayList<Job>()
    private var userDisconnect = false
    private var lastAlarm = 0L
    private var lastBatteryAlert = 101

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ---------------- log ----------------
    fun addLog(dir: Char, text: String) {
        _log.update { (it + LogLine(System.currentTimeMillis(), dir, text)).takeLast(400) }
    }
    fun clearLog() { _log.value = emptyList() }

    // ---------------- scan ----------------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids.orEmpty()
            val voltix = uuids.any { it.uuid == Voltix.SERVICE }
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "(bez nazwy)"
            val item = ScanItem(result.device.address, name, result.rssi, voltix)
            _scan.update { list ->
                (list.filterNot { it.address == item.address } + item)
                    .sortedWith(compareByDescending<ScanItem> { it.voltix }.thenByDescending { it.rssi })
            }
        }
        override fun onScanFailed(errorCode: Int) {
            addLog('•', "Skanowanie nieudane: $errorCode")
            _state.value = ConnState.DISCONNECTED
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { addLog('•', "Bluetooth wyłączony"); return }
        _scan.value = emptyList()
        _state.value = ConnState.SCANNING
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCb)
        scope.launch {
            delay(15_000)
            if (_state.value == ConnState.SCANNING) stopScan()
        }
    }

    fun stopScan() {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        if (_state.value == ConnState.SCANNING) _state.value = ConnState.DISCONNECTED
    }

    // ---------------- connect ----------------
    fun connect(address: String, name: String? = null) {
        stopScan()
        val dev: BluetoothDevice = (try { adapter?.getRemoteDevice(address) } catch (e: Exception) { null }) ?: return
        closeGatt()
        userDisconnect = false
        prefs.lastAddress = address
        if (name != null) prefs.lastName = name
        _deviceName.value = name ?: prefs.lastName ?: address
        _state.value = ConnState.CONNECTING
        addLog('•', "Łączenie z $address…")
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun reconnectLast(): Boolean {
        val a = prefs.lastAddress ?: return false
        if (_state.value == ConnState.CONNECTED || _state.value == ConnState.CONNECTING) return true
        if (!bluetoothOn) return false
        connect(a, prefs.lastName)
        return true
    }

    fun disconnect() {
        userDisconnect = true
        closeGatt()
        _state.value = ConnState.DISCONNECTED
        addLog('•', "Rozłączono")
    }

    fun forget() {
        disconnect()
        prefs.lastAddress = null
        prefs.lastName = null
    }

    private fun closeGatt() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
        writeChar = null
        rx.clear()
        while (writeQueue.tryReceive().isSuccess) { /* opróżnij */ }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    addLog('•', "Połączono (GATT), wykrywanie usług…")
                    g.requestMtu(512)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    addLog('•', "Rozłączono (status $status)")
                    val wasUser = userDisconnect
                    closeGatt()
                    _state.value = ConnState.DISCONNECTED
                    if (!wasUser && prefs.autoReconnect) {
                        delay(3000)
                        if (_state.value == ConnState.DISCONNECTED && !userDisconnect) reconnectLast()
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch {
                addLog('•', "MTU = $mtu")
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch {
                val svc = g.getService(Voltix.SERVICE)
                if (svc == null) {
                    addLog('•', "Brak usługi FF60! Usługi: " + g.services.joinToString { it.uuid.toString().substring(4, 8) })
                    return@launch
                }
                val notify = svc.getCharacteristic(Voltix.NOTIFY)
                writeChar = svc.getCharacteristic(Voltix.WRITE)
                if (notify == null || writeChar == null) {
                    addLog('•', "Brak charakterystyk FF61/FF62")
                    return@launch
                }
                g.setCharacteristicNotification(notify, true)
                val d = notify.getDescriptor(Voltix.CCCD)
                if (d != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(d)
                    }
                } else startSession()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            scope.launch { addLog('•', "Notyfikacje FF61 włączone"); startSession() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck.trySend(Unit)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            onNotify(c, v)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(c, value)
        }

        private fun onNotify(c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != Voltix.NOTIFY) return
            val copy = value.copyOf()
            scope.launch {
                copy.forEach { rx.add(it) }
                val frames = Voltix.extractFrames(rx)
                if (frames.isEmpty()) addLog('←', Voltix.hex(copy) + "  (fragment)")
                frames.forEach { handle(it) }
            }
        }
    }

    private fun startSession() {
        _state.value = ConnState.CONNECTED
        // Kolejka zapisu – Android pozwala na 1 operację GATT naraz.
        sessionJobs += scope.launch {
            while (isActive) {
                val data = writeQueue.receive()
                writeRaw(data)
                withTimeoutOrNull(400) { writeAck.receive() }
                delay(30)
            }
        }
        // Sekwencja jak w Voltix: connect ×2, potem heartbeat co 1 s.
        // Telemetrię (0x20 / 0x21) hulajnoga wysyła sama – nie trzeba o nią pytać.
        sessionJobs += scope.launch {
            send(Voltix.connectFrame(), "connect")
            delay(500)
            send(Voltix.connectFrame(), "connect")
            delay(500)
            send(Voltix.query(Voltix.QUERY_SN), "serial")
            send(Voltix.query(Voltix.QUERY_SPEED_LIMIT), "zakres limitu")
            var n = 0
            while (isActive) {
                n++
                send(Voltix.heartbeatFrame(), null)
                if (prefs.bmsPoll) {
                    if (n % 3 == 0) send(Voltix.query(Voltix.QUERY_BMS_VOLTAGE, 1), null)
                    if (n % 6 == 0) send(Voltix.query(Voltix.QUERY_BMS_INFO, 1), null)
                }
                delay(1000)
            }
        }
    }

    private fun writeRaw(data: ByteArray) {
        val g = gatt ?: return
        val c = writeChar ?: return
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, type)
        } else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    /** Wysyła ramkę. note == null -> nie loguj (np. cykliczne zapytania). */
    fun send(frame: ByteArray, note: String? = ""): Boolean {
        if (frame.size >= 3 && (frame[2].toInt() and 0xFF) in Voltix.FORBIDDEN) {
            addLog('•', "ZABLOKOWANO niebezpieczną komendę " + Voltix.hex(frame))
            return false
        }
        if (_state.value != ConnState.CONNECTED) {
            if (note != null) addLog('•', "Nie połączono – nie wysłano")
            return false
        }
        if (note != null) addLog('→', Voltix.hex(frame) + if (note.isNotEmpty()) "  $note" else "")
        writeQueue.trySend(frame)
        return true
    }

    fun queryInfo() {
        send(Voltix.query(Voltix.QUERY_SN), "serial")
        send(Voltix.query(Voltix.QUERY_SPEED_LIMIT), "zakres limitu")
        send(Voltix.query(Voltix.QUERY_BMS_VOLTAGE, 1), "BMS ogniwa")
        send(Voltix.query(Voltix.QUERY_BMS_INFO, 1), "BMS info")
        send(Voltix.query(Voltix.QUERY_FIRMWARE, 1), "firmware")
    }

    private val lastRaw = HashMap<Int, ByteArray>()

    private fun handle(f: ByteArray) {
        val p = Voltix.payload(f)
        val cmd = f[2].toInt() and 0xFF
        val name = Voltix.NAMES[cmd] ?: "0x%02X".format(cmd)
        val crc = if (Voltix.crcOk(f)) "" else "  CRC?"
        // Cykliczne ramki logujemy tylko gdy się zmieniły.
        val prev = lastRaw[cmd]
        if (prev == null || !prev.contentEquals(f)) {
            addLog('←', Voltix.hex(f) + "  " + name + crc)
            lastRaw[cmd] = f
        }
        when (cmd) {
            Voltix.METER1 -> {
                _telemetry.update { it.withMeter1(f) }
                analytics.onTelemetry(_telemetry.value)
                checkSpeedAlarm()
                checkBattery()
                checkTheft()
            }
            Voltix.METER2 -> _telemetry.update { it.withMeter2(f) }
            Voltix.QUERY_SPEED_LIMIT -> if (p.size >= 2) {
                val mn = p[0].toInt() and 0xFF
                val mx = p[1].toInt() and 0xFF
                _telemetry.update { it.copy(limitMin = mn, limitMax = mx) }
            }
            Voltix.QUERY_SN -> {
                val s = String(p.filter { it in 0x20..0x7E }.toByteArray(), Charsets.US_ASCII)
                if (s.isNotBlank()) _telemetry.update { it.copy(serial = s) }
            }
            Voltix.QUERY_FIRMWARE -> _telemetry.update { it.copy(firmware = Voltix.hex(p)) }
            Voltix.QUERY_BMS_VOLTAGE -> _telemetry.update { it.withCells(f) }
            Voltix.QUERY_BMS_INFO -> _telemetry.update { it.withBmsInfo(f) }
        }
    }

    private fun checkSpeedAlarm() {
        val limit = prefs.speedAlarmKmh
        if (limit <= 0) return
        val now = System.currentTimeMillis()
        if (_telemetry.value.speedKmh > limit && now - lastAlarm > 3000) {
            lastAlarm = now
            val vib = ctx.getSystemService(Vibrator::class.java) ?: return
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200), -1))
        }
    }

    private fun checkBattery() {
        val b = _telemetry.value.batteryPct
        if (!prefs.batteryAlerts || b <= 0) return
        for (level in intArrayOf(20, 10, 5)) {
            if (b <= level && lastBatteryAlert > level) {
                pl.kret.w9.Notifications.show(ctx, 10 + level, "Bateria $b%", "Hulajnoga ma mało energii – czas wracać i ładować.")
                lastBatteryAlert = level
                return
            }
        }
        if (b > 25) lastBatteryAlert = 101
    }

    private var theftArmedAt = 0L
    private fun checkTheft() {
        val t = _telemetry.value
        if (!prefs.theftAlarm || !t.locked) {
            if (_alarm.value) stopAlarm()
            theftArmedAt = if (t.locked) theftArmedAt else 0L
            return
        }
        // uzbrajamy po 3 s od zablokowania, żeby nie wyło przy samym zapięciu
        if (theftArmedAt == 0L) theftArmedAt = System.currentTimeMillis()
        if (System.currentTimeMillis() - theftArmedAt < 3000) return
        // ruch zablokowanej hulajnogi = próba kradzieży
        if (t.speedKmh > 0.5 && !_alarm.value) {
            _alarm.value = true
            pl.kret.w9.Siren.maxVolume(ctx.getSystemService(android.media.AudioManager::class.java))
            pl.kret.w9.Siren.start()
            val vib = ctx.getSystemService(Vibrator::class.java)
            vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400, 150, 400), 0))
            pl.kret.w9.Notifications.show(ctx, 99, "Alarm!", "Ktoś rusza Twoją zablokowaną hulajnogą.")
        }
    }

    fun stopAlarm() {
        _alarm.value = false
        theftArmedAt = System.currentTimeMillis()
        pl.kret.w9.Siren.stop()
        ctx.getSystemService(Vibrator::class.java)?.cancel()
    }

    /** Profil = kilka komend; hulajnoga potrzebuje chwili między nimi (Voltix czeka na odpowiedź). */
    fun applyProfile(p: pl.kret.w9.RideProfile) {
        if (_telemetry.value.speedKmh > 0.5) { addLog('•', "Profil ustawisz tylko na postoju"); return }
        prefs.kers = p.kers
        prefs.zeroStart = p.zeroStart
        addLog('•', "Profil: ${p.name}")
        scope.launch {
            setGear(p.gear); delay(700)
            setSpeedLimit(p.limitKmh); delay(700)
            setEBrake(p.kers); delay(700)
            setZeroStart(p.zeroStart); delay(700)
            send(Voltix.query(Voltix.QUERY_SPEED_LIMIT), null)
        }
    }

    // ---------------- sterowanie ----------------
    /** Tryb jazdy: 0 spacer, 1 eko, 2 normalny, 3 sport (tylko na postoju). */
    fun setGear(g: Int) {
        _gear.value = g
        prefs.lastGear = g
        send(Voltix.build(Voltix.GEAR, 2, g, 0), "tryb $g")
    }

    /*
     * Blokada wg Voltix (cmd 0x32): typ 0 = odblokuj, 1 = zablokuj z PIN-em, 2 = blokada tymczasowa (bez PIN),
     * 3 = usuń PIN. Po typie opcjonalnie 4 cyfry PIN-u.
     */
    private fun lockFrame(type: Int, pin: String?): ByteArray {
        val digits = pin?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.map { it.digitToInt() } ?: emptyList()
        return Voltix.build(Voltix.LOCK, 2, *(intArrayOf(type) + digits.toIntArray()))
    }

    /** Zablokuj: z PIN-em gdy podany (typ 1), inaczej blokada tymczasowa (typ 2). */
    fun lock(pin: String? = null): Boolean {
        if (_telemetry.value.speedKmh > 0.5) { addLog('•', "Zablokować można tylko na postoju"); return false }
        val p = pin?.takeIf { it.length == 4 }
        return if (p != null) send(lockFrame(1, p), "zablokuj z PIN") else send(lockFrame(2, null), "zablokuj")
    }

    fun unlock(pin: String?) {
        val p = pin?.takeIf { it.length == 4 && _telemetry.value.lockHasPin }
        send(lockFrame(0, p), "odblokuj")
    }

    fun clearLockPin(pin: String) = send(lockFrame(3, pin), "usuń PIN blokady")

    /**
     * Podświetlenie otoczenia RGB (cmd 0x33, pierwszy bajt 02).
     * Tryby 0–1: jeden kolor; tryby 2–3: efekt z kilkoma kolorami. Jasność 0–100.
     */
    fun setAmbient(mode: Int, brightness: Int, colors: List<Int>): Boolean {
        val b = brightness.coerceIn(0, 100)
        val rgb = colors.flatMap { listOf((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) }
        val frame = if (mode <= 1 && colors.isNotEmpty()) {
            Voltix.build(Voltix.LIGHT, 2, 2, b, mode, 1, rgb[0], rgb[1], rgb[2])
        } else {
            val list = if (mode <= 1) emptyList() else rgb.take(9)
            Voltix.build(Voltix.LIGHT, 2, *(intArrayOf(2, b, mode, list.size / 3) + list.toIntArray()))
        }
        return send(frame, "podświetlenie tryb $mode, ${b}%")
    }

    fun toggleLock() = if (_telemetry.value.locked) unlock(prefs.pin) else { lock(prefs.pin.takeIf { prefs.lockWithPin }); Unit }

    /** Przednie światło: [00 FA 00 00 00 00] = ON, same zera = OFF (jak w Voltix). */
    fun setLight(on: Boolean) =
        send(if (on) Voltix.build(Voltix.LIGHT, 2, 0, 250, 0, 0, 0, 0) else Voltix.build(Voltix.LIGHT, 2, 0, 0, 0, 0, 0, 0), "światło ${if (on) "ON" else "OFF"}")

    /** Tempomat: [05 03] = ON, [00 03] = OFF. */
    fun setCruise(on: Boolean) = send(Voltix.build(Voltix.CRUISE, 2, if (on) 5 else 0, 3), "tempomat ${if (on) "ON" else "OFF"}")

    /** Limit prędkości (km/h) – hulajnoga przyjmuje go tylko na postoju i w zakresie min–max. */
    fun setSpeedLimit(kmh: Int): Boolean {
        val t = _telemetry.value
        // hulajnoga liczy limit w jednostce ustawionej na wyświetlaczu
        val unitVal = if (t.mphUnit) Math.round(kmh * 0.621371).toInt() else kmh
        val v = unitVal.coerceIn(t.limitMin ?: 1, t.limitMax ?: 99)
        if (v != unitVal) addLog('•', "Hulajnoga przyjmuje limit ${t.limitMin}–${t.limitMax} – ustawiam $v")
        if (t.speedKmh > 0.5) addLog('•', "Limit zmienisz tylko na postoju")
        return send(Voltix.build(Voltix.SPEED_LIMIT, 2, v), "limit $v ${if (t.mphUnit) "mph" else "km/h"}")
    }

    /** Jasność wyświetlacza (cmd 0x34). Aktualną wartość hulajnoga podaje w ramce 0x21. */
    fun setBrightness(level: Int) = send(Voltix.build(Voltix.GAUGE_BRIGHTNESS, 2, level.coerceIn(0, 100)), "jasność wyświetlacza $level")

    fun setUnit(mph: Boolean) = send(Voltix.build(Voltix.UNIT, 2, if (mph) 1 else 0), "jednostka ${if (mph) "mph" else "km/h"}")

    /** Rekuperacja: 0 adaptacyjna, 1 słaba, 2 średnia, 3 mocna. */
    fun setEBrake(level: Int) = send(Voltix.build(Voltix.E_BRAKE, 2, level.coerceIn(0, 3)), "KERS $level")
    fun setZeroStart(on: Boolean) = send(Voltix.build(Voltix.START_MODE, 2, if (on) 1 else 0, 0), "start od zera ${if (on) "ON" else "OFF"}")
    fun setSmartLock(on: Boolean) = send(Voltix.build(Voltix.SMART_LOCK, 2, if (on) 1 else 0), "smart lock ${if (on) "ON" else "OFF"}")
    /** Napęd: 0 = jeden silnik, 1 = dwa silniki (tylko modele 2×silnik). */
    fun setDriveMode(mode: Int) = send(Voltix.build(Voltix.DRIVE_MODE, 2, mode), "napęd $mode")

    fun sendHex(hex: String, addFraming: Boolean): Boolean {
        val b = Voltix.parseHex(hex) ?: run { addLog('•', "Zły HEX"); return false }
        return if (addFraming) {
            if (b.size < 2) { addLog('•', "Podaj co najmniej CMD i PARAM"); return false }
            send(Voltix.frame(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, b.copyOfRange(2, b.size)), "raw")
        } else send(b, "raw")
    }
}
