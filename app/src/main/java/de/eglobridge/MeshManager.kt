package de.eglobridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock

data class LightStatus(
    val meshId: Int,
    val online: Boolean,
    val on: Boolean,
    val colorMode: Boolean,
    val sequenceMode: Boolean,
    val candleMode: Boolean,
    val whiteBrightness: Int,
    val temperature: Int,
    val colorBrightness: Int,
    val r: Int,
    val g: Int,
    val b: Int
)

interface MeshListener {
    fun onMeshState(state: String)
    fun onStatus(status: LightStatus)
    fun onDeviceOffline(meshId: Int)
}

/**
 * Manages up to [MAX_CONNECTIONS] simultaneous BLE connections into the
 * AwoX/EGLO (Telink) mesh. The first connection goes to the best visible lamp;
 * lamps that are not heard through the mesh get their own direct connection
 * when they are visible in a scan. All work runs on the handler thread.
 */
@SuppressLint("MissingPermission")
class MeshManager(
    private val context: Context,
    private val handler: Handler,
    private val listener: MeshListener
) : MeshConnection.Events {

    companion object {
        const val AWOX_MANUFACTURER_ID = 0x0160

        const val C_POWER = 0xd0
        const val C_COLOR = 0xe2
        const val C_COLOR_BRIGHTNESS = 0xf2
        const val C_WHITE_BRIGHTNESS = 0xf1
        const val C_WHITE_TEMPERATURE = 0xf0
        const val C_REQUEST_STATUS = 0xda
        const val C_PRESET = 0xc8
        const val C_CANDLE = 0xc9
        const val C_SEQUENCE_COLOR_DURATION = 0xf5
        const val C_SEQUENCE_FADE_DURATION = 0xf6

        const val R_STATUS = 0xdb
        const val R_ONLINE_STATUS = 0xdc

        const val BROADCAST = 0xffff

        private const val MAX_CONNECTIONS = 3
        private const val SCAN_MS = 6000L
        private const val TICK_MS = 30000L
        private const val MISSING_AFTER_MS = 90 * 1000L
        private const val OFFLINE_AFTER_MS = 5 * 60 * 1000L
        private const val MIN_RSSI = -97
    }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager.adapter

    private var running = false
    private var meshName = ByteArray(0)
    private var meshPassword = ByteArray(0)
    private var devices: List<MeshDevice> = emptyList()
    private val lights get() = devices.filter { it.isLight }

    private val connections = LinkedHashMap<String, MeshConnection>()
    private var connecting: MeshConnection? = null
    private var scanning = false
    private var scanPurpose = ""
    private val scanRssi = HashMap<String, Int>()
    private val scanSeenAt = HashMap<String, Long>()

    private var candidates = ArrayDeque<String>()
    private val attemptsForMac = HashMap<String, Int>()
    private var reconnectDelay = 5000L
    private var tick = 0
    private var primaryCycle = false
    private var futileScans = 0

    private val lastSeen = HashMap<Int, Long>()
    private val online = HashSet<Int>()
    private val heardVia = HashMap<Int, String>()
    private var lastStateText = ""

    // ---------------------------------------------------------------- public API

    fun start(cfg: Config) {
        handler.post {
            meshName = cfg.meshName.toByteArray()
            meshPassword = cfg.meshPassword.toByteArray()
            devices = cfg.devices
            if (meshName.size > 16 || meshPassword.size > 16) {
                BridgeLog.e("Mesh-Name/Passwort länger als 16 Bytes – ungültig")
                return@post
            }
            running = true
            BridgeLog.i("Mesh-Bridge startet mit ${lights.size} Lampen")
            publishState()
            scanAndConnect("Start")
            handler.postDelayed(tickRunnable, TICK_MS)
        }
    }

    fun stop() {
        handler.post {
            running = false
            handler.removeCallbacks(tickRunnable)
            handler.removeCallbacks(reconnectRunnable)
            stopScan()
            connecting?.close("Stopp")
            connections.values.toList().forEach { it.close("Stopp") }
            connections.clear()
            publishState()
        }
    }

    fun reconnect() {
        handler.post {
            BridgeLog.i("Neuverbindung angefordert")
            connecting?.close("Neuverbindung")
            connections.values.toList().forEach { it.close("Neuverbindung") }
            attemptsForMac.clear()
            reconnectDelay = 3000L
            scheduleReconnect(1500)
        }
    }

    fun deviceName(meshId: Int) = devices.firstOrNull { it.meshId == meshId }?.name?.trim() ?: "#$meshId"

    // ---------------------------------------------------------------- state text

    private fun publishState() {
        val paired = connections.values.filter { it.isPaired }
        val text = when {
            !running -> "gestoppt"
            paired.isNotEmpty() -> "verbunden: " + paired.joinToString { c ->
                val d = devices.firstOrNull { it.mac == c.mac }
                (d?.name?.trim() ?: c.mac) + " (" + c.mac.takeLast(5) + ")"
            }
            connecting != null -> "verbinde mit ${connecting!!.mac}"
            scanning -> "suche Lampen"
            else -> "warte auf Lampen"
        }
        if (text != lastStateText) {
            lastStateText = text
            listener.onMeshState(text)
        }
    }

    // ---------------------------------------------------------------- periodic maintenance

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            tick++
            val paired = connections.values.filter { it.isPaired }
            if (paired.isNotEmpty()) {
                paired.forEach { it.send(BROADCAST, C_REQUEST_STATUS, byteArrayOf(0x10)) }
            }
            val now = SystemClock.elapsedRealtime()
            for (id in online.toList()) {
                if (now - (lastSeen[id] ?: 0L) > OFFLINE_AFTER_MS) {
                    online.remove(id)
                    heardVia.remove(id)
                    BridgeLog.w("${deviceName(id)} seit 5 Minuten ohne Meldung -> offline")
                    listener.onDeviceOffline(id)
                }
            }
            // look for lamps that are not reachable through the existing connections:
            // every minute at first, every 10 minutes after repeated misses
            val every = if (futileScans < 3) 2 else 20
            if (paired.isNotEmpty() && tick % every == 0 && connecting == null && !scanning &&
                connections.size < MAX_CONNECTIONS && missingLights().isNotEmpty()
            ) {
                scanForMissing()
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun missingLights(): List<MeshDevice> {
        val now = SystemClock.elapsedRealtime()
        return lights.filter { d ->
            now - (lastSeen[d.meshId] ?: 0L) > MISSING_AFTER_MS && !connections.containsKey(d.mac)
        }
    }

    // ---------------------------------------------------------------- scanning

    private fun hasLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address.uppercase()
            val known = devices.any { it.mac == mac }
            val awox = result.scanRecord?.getManufacturerSpecificData(AWOX_MANUFACTURER_ID) != null
            if (!known && !awox) return
            val rssi = result.rssi
            handler.post {
                if (!scanRssi.containsKey(mac)) {
                    val name = devices.firstOrNull { it.mac == mac }?.name?.trim() ?: "unbekannt"
                    BridgeLog.i("Gefunden: $name $mac RSSI $rssi")
                }
                scanRssi[mac] = rssi
                scanSeenAt[mac] = SystemClock.elapsedRealtime()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post { BridgeLog.w("Scan fehlgeschlagen (Code $errorCode)") }
        }
    }

    private fun startScan(purpose: String, then: () -> Unit) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !hasLocationPermission()) {
            if (!hasLocationPermission()) BridgeLog.w("Keine Standort-Berechtigung – verbinde ohne Scan")
            then(); return
        }
        scanRssi.clear()
        scanning = true
        scanPurpose = purpose
        publishState()
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, scanCallback)
        } catch (t: Throwable) {
            BridgeLog.e("Scan konnte nicht gestartet werden", t)
            scanning = false
            then(); return
        }
        handler.postDelayed({
            stopScan()
            scanning = false
            publishState()
            // give the controller a moment after stopping the scan
            handler.postDelayed({ if (running) then() }, 500)
        }, SCAN_MS)
    }

    private fun stopScan() {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Throwable) { }
    }

    // ---------------------------------------------------------------- connecting

    private fun scanAndConnect(why: String) {
        if (!running || connecting != null || scanning) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            BridgeLog.w("Bluetooth ist aus – neuer Versuch in 15 s")
            scheduleReconnect(15000L)
            return
        }
        if (lights.isEmpty()) {
            BridgeLog.e("Keine Lampen konfiguriert – bitte zuerst aus der AwoX-Cloud importieren")
            return
        }
        startScan(why) {
            val known = lights.map { it.mac }.distinct().filter { !connections.containsKey(it) }
            val seen = known.filter { scanRssi.containsKey(it) }.sortedByDescending { scanRssi[it] }
            val unseen = known.filter { !scanRssi.containsKey(it) }
            candidates = ArrayDeque(seen + unseen)
            attemptsForMac.clear()
            primaryCycle = true
            BridgeLog.i("Im Scan gefunden: " + if (seen.isEmpty()) "keine bekannte Lampe" else
                seen.joinToString { "${nameForMac(it)} (${scanRssi[it]})" })
            tryNextCandidate()
        }
    }

    private fun scanForMissing() {
        startScan("fehlende Lampen") {
            val missing = missingLights()
            val visible = missing.filter { (scanRssi[it.mac] ?: -999) >= MIN_RSSI }
                .sortedByDescending { scanRssi[it.mac] }
            if (visible.isEmpty()) {
                futileScans++
                BridgeLog.i("Nicht erreichbar: " + missing.joinToString { it.name.trim() } +
                    " – nicht direkt sichtbar")
                return@startScan
            }
            primaryCycle = false
            BridgeLog.i("Direktverbindung zusätzlich zu: " +
                visible.joinToString { "${it.name.trim()} (${scanRssi[it.mac]})" })
            candidates = ArrayDeque(visible.map { it.mac })
            attemptsForMac.clear()
            tryNextCandidate()
        }
    }

    private fun nameForMac(mac: String) = devices.firstOrNull { it.mac == mac }?.name?.trim() ?: mac

    private fun tryNextCandidate() {
        if (!running || connecting != null) return
        if (connections.size >= MAX_CONNECTIONS) return
        var mac = candidates.removeFirstOrNull()
        while (mac != null && connections.containsKey(mac)) mac = candidates.removeFirstOrNull()
        if (mac == null) {
            if (connections.values.none { it.isPaired }) {
                BridgeLog.w("Keine Lampe erreichbar – neuer Versuch in ${reconnectDelay / 1000} s")
                scheduleReconnect(reconnectDelay)
                reconnectDelay = minOf(reconnectDelay * 2, 120000L)
            }
            publishState()
            return
        }
        val device = try { adapter!!.getRemoteDevice(mac) } catch (t: Throwable) {
            BridgeLog.e("Ungültige MAC $mac", t); tryNextCandidate(); return
        }
        val auto = (attemptsForMac[mac] ?: 0) >= 1
        val c = MeshConnection(context, handler, mac, meshName, meshPassword, auto, this)
        connecting = c
        connections[mac] = c
        publishState()
        c.connect(device)
    }

    private fun scheduleReconnect(delay: Long) {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    private val reconnectRunnable = Runnable {
        if (running && connections.values.none { it.isPaired }) scanAndConnect("Neuverbindung")
    }

    // ---------------------------------------------------------------- MeshConnection.Events

    override fun onPaired(c: MeshConnection) {
        if (connecting == c) connecting = null
        reconnectDelay = 5000L
        attemptsForMac.remove(c.mac)
        if (primaryCycle) {
            // first connection is up: further lamps are only added by the periodic
            // "missing lamps" scan, so the link is not disturbed by hopeless attempts
            candidates.clear()
            primaryCycle = false
        } else {
            futileScans = 0
        }
        publishState()
        c.sendFirst(BROADCAST, C_REQUEST_STATUS, byteArrayOf(0x10))
        // ask the directly connected lamp itself, too
        devices.firstOrNull { it.mac == c.mac }?.let { c.send(it.meshId, C_REQUEST_STATUS, byteArrayOf(0x10)) }
        // continue with remaining candidates (additional direct connections)
        handler.postDelayed({ tryNextCandidate() }, 1000)
    }

    override fun onClosed(c: MeshConnection, wasPaired: Boolean, reason: String) {
        if (connections[c.mac] == c) connections.remove(c.mac)
        val wasConnecting = connecting == c
        if (wasConnecting) connecting = null
        heardVia.entries.removeAll { it.value == c.mac }
        if (!running) return

        if (wasPaired) {
            BridgeLog.w("Verbindung zu ${nameForMac(c.mac)} getrennt ($reason)")
        } else {
            BridgeLog.w("Verbindung zu ${nameForMac(c.mac)} fehlgeschlagen ($reason)")
            val n = (attemptsForMac[c.mac] ?: 0) + 1
            attemptsForMac[c.mac] = n
            // Android often fails the first attempt (GATT 133 / timeout): retry once in autoConnect mode
            if (n < 2 && scanRssi.containsKey(c.mac)) candidates.addFirst(c.mac)
            else if (connections.values.any { it.isPaired }) futileScans++
        }

        if (connections.values.none { it.isPaired }) {
            for (id in online.toList()) listener.onDeviceOffline(id)
            online.clear()
        }
        publishState()

        if (wasConnecting && candidates.isNotEmpty()) {
            handler.postDelayed({ tryNextCandidate() }, 1500)
        } else if (connections.values.none { it.isPaired } && connecting == null) {
            if (wasPaired) {
                reconnectDelay = 3000L
                scheduleReconnect(reconnectDelay)
            } else {
                handler.postDelayed({ tryNextCandidate() }, 1500)
            }
        }
    }

    private fun u(b: Byte) = b.toInt() and 0xff

    override fun onPacket(c: MeshConnection, p: ByteArray) {
        val cmd = u(p[7])
        if (BridgeLog.verbose) {
            BridgeLog.i("RX %02x via %s: %s".format(cmd, c.mac.takeLast(5), MeshCrypto.hex(p)))
        }
        val status = when (cmd) {
            R_ONLINE_STATUS -> {
                val mode = u(p[12])
                LightStatus(
                    meshId = u(p[19]) * 256 + u(p[10]),
                    online = u(p[11]) != 0,
                    on = (mode and 1) == 1,
                    colorMode = ((mode shr 1) and 1) == 1,
                    sequenceMode = ((mode shr 2) and 1) == 1,
                    candleMode = ((mode shr 4) and 1) == 1,
                    whiteBrightness = u(p[13]), temperature = u(p[14]), colorBrightness = u(p[15]),
                    r = u(p[16]), g = u(p[17]), b = u(p[18])
                )
            }
            R_STATUS -> {
                val mode = u(p[10])
                LightStatus(
                    meshId = u(p[4]) * 256 + u(p[3]),
                    online = true,
                    on = (mode and 1) == 1,
                    colorMode = ((mode shr 1) and 1) == 1,
                    sequenceMode = ((mode shr 2) and 1) == 1,
                    candleMode = ((mode shr 4) and 1) == 1,
                    whiteBrightness = u(p[11]), temperature = u(p[12]), colorBrightness = u(p[13]),
                    r = u(p[14]), g = u(p[15]), b = u(p[16])
                )
            }
            else -> null
        } ?: return

        if (devices.none { it.meshId == status.meshId }) {
            if (BridgeLog.verbose) BridgeLog.w("Meldung von unbekannter Mesh-ID ${status.meshId}")
            return
        }
        if (status.online) {
            lastSeen[status.meshId] = SystemClock.elapsedRealtime()
            heardVia[status.meshId] = c.mac
            if (online.add(status.meshId)) {
                BridgeLog.i("${deviceName(status.meshId)} online (über ${nameForMac(c.mac)})")
            }
            listener.onStatus(status)
        } else {
            if (online.remove(status.meshId)) BridgeLog.w("${deviceName(status.meshId)} meldet offline")
            heardVia.remove(status.meshId)
            listener.onDeviceOffline(status.meshId)
        }
    }

    // ---------------------------------------------------------------- commands (thread safe)

    private fun route(dest: Int, command: Int, data: ByteArray, coalesce: Boolean) {
        handler.post {
            val paired = connections.values.filter { it.isPaired }
            if (paired.isEmpty()) {
                BridgeLog.w("Nicht verbunden – Befehl %02x für %s verworfen".format(command, deviceName(dest)))
                return@post
            }
            if (dest == BROADCAST) {
                paired.forEach { it.send(dest, command, data, coalesce) }
                return@post
            }
            val d = devices.firstOrNull { it.meshId == dest }
            val direct = d?.let { connections[it.mac] }?.takeIf { it.isPaired }
            val via = heardVia[dest]?.let { connections[it] }?.takeIf { it.isPaired }
            when {
                direct != null -> direct.send(dest, command, data, coalesce)
                via != null -> via.send(dest, command, data, coalesce)
                // unknown route: send through every connection, the mesh will forward it
                else -> paired.forEach { it.send(dest, command, data, coalesce) }
            }
        }
    }

    fun setPower(dest: Int, on: Boolean) = route(dest, C_POWER, byteArrayOf(if (on) 1 else 0, 0, 0), false)
    fun setColor(dest: Int, r: Int, g: Int, b: Int, coalesce: Boolean = true) =
        route(dest, C_COLOR, byteArrayOf(0x04, r.toByte(), g.toByte(), b.toByte()), coalesce)
    fun setColorBrightness(dest: Int, v: Int, coalesce: Boolean = true) =
        route(dest, C_COLOR_BRIGHTNESS, byteArrayOf(v.toByte()), coalesce)
    fun setWhiteBrightness(dest: Int, v: Int) = route(dest, C_WHITE_BRIGHTNESS, byteArrayOf(v.toByte()), true)
    fun setWhiteTemperature(dest: Int, v: Int) = route(dest, C_WHITE_TEMPERATURE, byteArrayOf(v.toByte()), true)
    fun setPreset(dest: Int, preset: Int) = route(dest, C_PRESET, byteArrayOf(preset.toByte()), false)
    fun setCandle(dest: Int) = route(dest, C_CANDLE, byteArrayOf(0), false)
    fun setSequenceColorDuration(dest: Int, ms: Int) = route(dest, C_SEQUENCE_COLOR_DURATION, le32(ms), true)
    fun setSequenceFadeDuration(dest: Int, ms: Int) = route(dest, C_SEQUENCE_FADE_DURATION, le32(ms), true)
    fun requestStatus(dest: Int) = route(dest, C_REQUEST_STATUS, byteArrayOf(0x10), true)

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
    )
}
