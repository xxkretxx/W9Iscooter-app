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
    private var pollSub: Int? = null
    private var pollsWithoutReply = 0
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
        pollSub = null
        pollsWithoutReply = 0
        // Kolejka zapisu – Android pozwala na 1 operację GATT naraz.
        sessionJobs += scope.launch {
            while (isActive) {
                val data = writeQueue.receive()
                writeRaw(data)
                withTimeoutOrNull(400) { writeAck.receive() }
                delay(30)
            }
        }
        sessionJobs += scope.launch {
            send(Voltix.connectFrame(), "connect")
            delay(1000)
            send(Voltix.connectFrame(), "connect")
            delay(300)
            send(Voltix.heartbeatFrame(), "heartbeat")
            delay(300)
            queryInfo()
            var n = 0
            while (isActive) {
                n++
                send(Voltix.queryCarFrame(pollSub), null)
                if (prefs.bmsPoll) {
                    if (n % 2 == 0) send(Voltix.query(Voltix.QUERY_BMS_VOLTAGE, 0), null)
                    if (n % 15 == 0) send(Voltix.query(Voltix.QUERY_BMS_INFO, 0), null)
                }
                pollsWithoutReply++
                if (pollsWithoutReply == 4) {
                    pollSub = if (pollSub == null) 0x01 else null
                    addLog('•', "Brak telemetrii – zmieniam zapytanie na " + Voltix.hex(Voltix.queryCarFrame(pollSub)))
                    pollsWithoutReply = 0
                }
                val accel = analytics.accelState.value != AccelState.IDLE
                delay(if (fastPoll || accel) 250L else prefs.pollMs.toLong())
            }
        }
        sessionJobs += scope.launch {
            while (isActive) {
                delay(10_000)
                send(Voltix.heartbeatFrame(), null)
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
        send(Voltix.query(Voltix.QUERY_SPEED_LIMIT), "limit")
        send(Voltix.query(Voltix.QUERY_LIGHT), "światła")
        send(Voltix.query(Voltix.QUERY_BMS_VOLTAGE, 0), "BMS napięcie")
        send(Voltix.query(Voltix.QUERY_BMS_INFO, 0), "BMS info")
        send(Voltix.query(Voltix.QUERY_FIRMWARE, 0), "firmware")
    }

    private fun handle(f: ByteArray) {
        val p = Voltix.payload(f)
        val cmd = f[2].toInt() and 0xFF
        val name = Voltix.NAMES[cmd] ?: "0x%02X".format(cmd)
        val crc = if (Voltix.crcOk(f)) "" else "  CRC?"
        val periodic = cmd == Voltix.QUERY_CAR || cmd == Voltix.QUERY_BMS_VOLTAGE || cmd == Voltix.QUERY_BMS_INFO
        val raw = when (cmd) {
            Voltix.QUERY_CAR -> _telemetry.value.rawQueryCar
            Voltix.QUERY_BMS_VOLTAGE -> _telemetry.value.rawBmsVoltage
            Voltix.QUERY_BMS_INFO -> _telemetry.value.rawBmsInfo
            else -> null
        }
        if (!periodic || raw == null || !p.contentEquals(raw)) {
            addLog('←', Voltix.hex(f) + "  " + name + crc)
        }
        when (cmd) {
            Voltix.QUERY_CAR -> {
                pollsWithoutReply = 0
                _telemetry.update { it.withQueryCar(p) }
                analytics.onTelemetry(_telemetry.value)
                checkSpeedAlarm()
                checkBattery()
            }
            Voltix.QUERY_SPEED_LIMIT -> {
                val v = p.firstOrNull()?.toInt()?.and(0xFF)
                if (v != null && v in 1..79) _telemetry.update { it.copy(speedLimitKmh = v) }
            }
            Voltix.QUERY_LIGHT, Voltix.LIGHT -> {
                p.firstOrNull()?.let { b -> _telemetry.update { it.copy(lightFlags = b.toInt() and 0xFF) } }
            }
            Voltix.QUERY_SN -> {
                val s = String(p.filter { it in 0x20..0x7E }.toByteArray(), Charsets.US_ASCII)
                if (s.isNotBlank()) _telemetry.update { it.copy(serial = s) }
            }
            Voltix.QUERY_FIRMWARE -> _telemetry.update { it.copy(firmware = Voltix.hex(p)) }
            Voltix.QUERY_BMS_VOLTAGE -> _telemetry.update { it.withMeter2(p).copy(rawBmsVoltage = p) }
            Voltix.QUERY_BMS_INFO -> _telemetry.update { it.withMeter2(p).copy(rawBmsInfo = p) }
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

    fun applyProfile(p: pl.kret.w9.RideProfile) {
        setGear(p.gear)
        setSpeedLimit(p.limitKmh)
        setEBrake(p.kers)
        setZeroStart(p.zeroStart)
        prefs.kers = p.kers
        prefs.zeroStart = p.zeroStart
        addLog('•', "Profil: ${p.name}")
    }

    // ---------------- sterowanie ----------------
    fun setGear(g: Int) {
        _gear.value = g
        prefs.lastGear = g
        send(Voltix.build(Voltix.GEAR, 2, g), "bieg $g")
    }

    fun lock() = send(Voltix.build(Voltix.LOCK, 2, 0x00), "blokada")

    fun unlock(pin: String?) {
        val digits = pin?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.map { it.digitToInt() } ?: emptyList()
        send(Voltix.build(Voltix.LOCK, 2, *(intArrayOf(0x00) + digits.toIntArray())), "odblokuj")
    }

    fun setLight(on: Boolean) = send(Voltix.build(Voltix.LIGHT, 2, if (on) prefs.lightOnValue else 0x00), "światło ${if (on) "ON" else "OFF"}")
    fun setCruise(on: Boolean) = send(Voltix.build(Voltix.CRUISE, 2, if (on) 0x01 else 0x00), "tempomat ${if (on) "ON" else "OFF"}")
    fun setSpeedLimit(kmh: Int) = send(Voltix.build(Voltix.SPEED_LIMIT, 2, kmh), "limit $kmh km/h")
    fun setUnit(mph: Boolean) = send(Voltix.build(Voltix.UNIT, 2, if (mph) 1 else 0), "jednostka ${if (mph) "mph" else "km/h"}")
    fun setEBrake(level: Int) = send(Voltix.build(Voltix.E_BRAKE, 2, level), "hamulec el. $level")
    fun setZeroStart(on: Boolean) = send(Voltix.build(Voltix.START_MODE, 2, if (on) 1 else 0, 0), "start od zera ${if (on) "ON" else "OFF"}")
    fun setBrightness(level: Int) = send(Voltix.build(Voltix.GAUGE_BRIGHTNESS, 2, level), "jasność $level")
    fun setSmartLock(on: Boolean) = send(Voltix.build(Voltix.SMART_LOCK, 2, if (on) 1 else 0), "smart lock ${if (on) "ON" else "OFF"}")
    fun setDriveMode(mode: Int) = send(Voltix.build(Voltix.DRIVE_MODE, 2, mode), "tryb jazdy $mode")

    fun sendHex(hex: String, addFraming: Boolean): Boolean {
        val b = Voltix.parseHex(hex) ?: run { addLog('•', "Zły HEX"); return false }
        return if (addFraming) {
            if (b.size < 2) { addLog('•', "Podaj co najmniej CMD i PARAM"); return false }
            send(Voltix.frame(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, b.copyOfRange(2, b.size)), "raw")
        } else send(b, "raw")
    }
}
