package pl.kret.w9.ble

import java.util.UUID

/**
 * Protokół Voltix "FF60" (aplikacja com.world.voltix) – patrz PROTOCOL.md.
 * Ramka: [1A A1] [CMD] [PARAM] [LEN] [PAYLOAD…] [CRC lo] [CRC hi] [1F F1]
 * CRC-16/MODBUS liczone po bajtach CMD..PAYLOAD.
 */
object Voltix {
    val SERVICE: UUID = UUID.fromString("0000ff60-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("0000ff61-0000-1000-8000-00805f9b34fb")
    val WRITE: UUID = UUID.fromString("0000ff62-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val HEARTBEAT = 0x10
    const val CONNECT = 0x30
    const val POWER_ON = 0x31
    const val LOCK = 0x32
    const val LIGHT = 0x33
    const val GAUGE_BRIGHTNESS = 0x34
    const val GEAR = 0x35
    const val SPEED_LIMIT = 0x36
    const val E_BRAKE = 0x37
    const val CRUISE = 0x38
    const val START_MODE = 0x39
    const val DRIVE_MODE = 0x3B
    const val UNIT = 0x3C
    const val SINGLE_MILE = 0x3E
    const val SMART_LOCK = 0x47
    const val QUERY_CAR = 0x60
    const val QUERY_SN = 0x61
    const val QUERY_SPEED_LIMIT = 0x62
    const val QUERY_LIGHT = 0x63
    const val QUERY_BMS_VOLTAGE = 0x64
    const val QUERY_BMS_INFO = 0x65
    const val QUERY_FIRMWARE = 0x66

    /** Komendy od bindowania/resetu/firmware – NIGDY nie wysyłane (nawet z konsoli). */
    val FORBIDDEN = setOf(0x3A, 0x42, 0x43, 0x91, 0x92, 0x93)

    val NAMES = mapOf(
        HEARTBEAT to "heartbeat", CONNECT to "connect", POWER_ON to "power", LOCK to "lock",
        LIGHT to "light", GAUGE_BRIGHTNESS to "brightness", GEAR to "gear", SPEED_LIMIT to "speedLimit",
        E_BRAKE to "eBrake", CRUISE to "cruise", START_MODE to "startMode", DRIVE_MODE to "driveMode",
        UNIT to "unit", SINGLE_MILE to "singleMile", SMART_LOCK to "smartLock", QUERY_CAR to "queryCar",
        QUERY_SN to "querySN", QUERY_SPEED_LIMIT to "querySpeedLimit", QUERY_LIGHT to "queryLight",
        QUERY_BMS_VOLTAGE to "queryBmsVoltage", QUERY_BMS_INFO to "queryBmsInfo", QUERY_FIRMWARE to "queryFirmware",
    )

    fun crc16Modbus(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    fun frame(cmd: Int, param: Int, payload: ByteArray = ByteArray(0), crcBigEndian: Boolean = false): ByteArray {
        val body = ByteArray(3 + payload.size)
        body[0] = cmd.toByte(); body[1] = param.toByte(); body[2] = payload.size.toByte()
        payload.copyInto(body, 3)
        val crc = crc16Modbus(body)
        val lo = (crc and 0xFF).toByte()
        val hi = (crc ushr 8).toByte()
        return byteArrayOf(0x1A, 0xA1.toByte()) + body +
            (if (crcBigEndian) byteArrayOf(hi, lo) else byteArrayOf(lo, hi)) +
            byteArrayOf(0x1F, 0xF1.toByte())
    }

    /** Ramka z bajtami payloadu podanymi jako Int. */
    fun build(cmd: Int, param: Int, vararg payload: Int): ByteArray =
        frame(cmd, param, ByteArray(payload.size) { payload[it].toByte() })

    fun connectFrame() = build(CONNECT, 2, 0x01)
    fun heartbeatFrame() = build(HEARTBEAT, 1)
    fun queryCarFrame(sub: Int?) = if (sub == null) build(QUERY_CAR, 1) else build(QUERY_CAR, 1, sub)
    fun query(cmd: Int, sub: Int? = null) = if (sub == null) build(cmd, 1) else build(cmd, 1, sub)

    /** Dzieli strumień bajtów na całe ramki (notyfikacje mogą przychodzić pocięte lub sklejone). */
    fun extractFrames(buf: MutableList<Byte>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) {
            var start = -1
            for (i in 0 until buf.size - 1) {
                if (buf[i] == 0x1A.toByte() && buf[i + 1] == 0xA1.toByte()) { start = i; break }
            }
            if (start < 0) {
                if (buf.size > 1) { val last = buf.last(); buf.clear(); buf.add(last) }
                return out
            }
            if (start > 0) repeat(start) { buf.removeAt(0) }
            if (buf.size < 5) return out
            val total = 9 + (buf[4].toInt() and 0xFF)
            if (buf.size >= total && buf[total - 2] == 0x1F.toByte() && buf[total - 1] == 0xF1.toByte()) {
                out.add(buf.subList(0, total).toByteArray())
                repeat(total) { buf.removeAt(0) }
                continue
            }
            // Bajt długości się nie zgadza – szukamy najbliższej stopki.
            var end = -1
            for (i in 5 until buf.size - 1) {
                if (buf[i] == 0x1F.toByte() && buf[i + 1] == 0xF1.toByte()) { end = i; break }
            }
            if (end >= 0 && (buf.size >= total || end + 2 < total)) {
                out.add(buf.subList(0, end + 2).toByteArray())
                repeat(end + 2) { buf.removeAt(0) }
                continue
            }
            if (buf.size > 600) buf.clear()
            return out
        }
    }

    fun payload(f: ByteArray): ByteArray = if (f.size < 9) ByteArray(0) else f.copyOfRange(5, f.size - 4)

    fun crcOk(f: ByteArray): Boolean {
        if (f.size < 9) return false
        val crc = crc16Modbus(f, 2, f.size - 4)
        val lo = f[f.size - 4].toInt() and 0xFF
        val hi = f[f.size - 3].toInt() and 0xFF
        return (lo == (crc and 0xFF) && hi == (crc ushr 8)) || (hi == (crc and 0xFF) && lo == (crc ushr 8))
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun parseHex(s: String): ByteArray? {
        val clean = s.uppercase().filter { it in "0123456789ABCDEF" }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

data class Telemetry(
    val batteryPct: Int = 0,
    val faultCode: Int = 0,
    val speedKmh: Double = 0.0,
    val lightFlags: Int = 0,
    val statusFlags: Int = 0,
    val speedLimitKmh: Int? = null,
    val voltage: Double? = null,
    val currentA: Double? = null,
    val tempC: Double? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val rawBmsVoltage: ByteArray = ByteArray(0),
    val rawBmsInfo: ByteArray = ByteArray(0),
    val rawQueryCar: ByteArray = ByteArray(0),
    val lastUpdate: Long = 0L,
) {
    val lightOn get() = lightFlags and 0x01 != 0
    val locked get() = statusFlags and 0x02 != 0      // eksperymentalne
    val cruiseOn get() = statusFlags and 0x04 != 0    // eksperymentalne
    val charging get() = statusFlags and 0x08 != 0    // eksperymentalne
    val powerW: Double? get() = if (voltage != null && currentA != null) voltage * currentA else null

    fun withQueryCar(p: ByteArray): Telemetry {
        if (p.size < 5) return copy(rawQueryCar = p, lastUpdate = System.currentTimeMillis())
        val u = { i: Int -> p[i].toInt() and 0xFF }
        val high = if (p.size > 8) u(8) and 0x03 else 0
        val bat = u(3)
        val lim = if (p.size > 7) u(7) else 0
        return copy(
            lightFlags = u(0),
            statusFlags = u(1),
            faultCode = u(2),
            batteryPct = if (bat <= 100) bat else batteryPct,
            speedKmh = (u(4) or (high shl 8)) * 0.1,
            speedLimitKmh = if (lim in 1..79) lim else speedLimitKmh,
            rawQueryCar = p,
            lastUpdate = System.currentTimeMillis(),
        )
    }

    /**
     * Heurystyka: szuka w surowym pakiecie BMS wartości 16-bit LE wyglądających jak napięcia ogniw (2,5–4,35 V w mV).
     * Producent nie publikuje formatu – wynik trzeba porównać z rzeczywistością.
     */
    fun cellVoltages(): List<Double> {
        for (src in listOf(rawBmsInfo, rawBmsVoltage)) {
            val cells = ArrayList<Double>()
            var i = 0
            while (i + 1 < src.size) {
                val mv = (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8)
                if (mv in 2500..4350) cells.add(mv / 1000.0)
                i += 2
            }
            if (cells.size >= 4) return cells
        }
        return emptyList()
    }

    fun withMeter2(p: ByteArray): Telemetry {
        fun u16(i: Int): Int? = if (p.size > i + 1) (p[i].toInt() and 0xFF) or ((p[i + 1].toInt() and 0xFF) shl 8) else null
        val v = u16(2)?.let { it * 0.01 }
        return copy(
            voltage = if (v != null && v in 20.0..100.0) v else voltage,
            // prąd jako liczba ze znakiem – ujemny = rekuperacja (ładowanie przy hamowaniu)
            currentA = u16(4)?.let { (if (it > 32767) it - 65536 else it) * 0.01 } ?: currentA,
            tempC = if (p.size > 11) (p[11].toInt() and 0xFF) * 0.1 else tempC,
        )
    }
}
