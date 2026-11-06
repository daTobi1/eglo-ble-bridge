package de.eglobridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import java.security.SecureRandom
import java.util.UUID

data class LightStatus(
    val meshId: Int,
    val online: Boolean,
    val on: Boolean,
    val colorMode: Boolean,
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
 * Keeps one BLE connection to a lamp of the AwoX/EGLO (Telink) mesh and sends
 * commands through it. All work runs on the given handler's thread.
 */
@SuppressLint("MissingPermission")
class MeshManager(
    private val context: Context,
    private val handler: Handler,
    private val listener: MeshListener
) {
    companion object {
        val SERVICE: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1910")
        val CHAR_STATUS: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1911")
        val CHAR_COMMAND: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1912")
        val CHAR_PAIR: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1914")

        const val AWOX_MANUFACTURER_ID = 0x0160

        const val C_POWER = 0xd0
        const val C_COLOR = 0xe2
        const val C_COLOR_BRIGHTNESS = 0xf2
        const val C_WHITE_BRIGHTNESS = 0xf1
        const val C_WHITE_TEMPERATURE = 0xf0
        const val C_REQUEST_STATUS = 0xda

        const val R_STATUS = 0xdb
        const val R_ONLINE_STATUS = 0xdc

        private const val COMMAND_SPACING_MS = 180L
        private const val OP_TIMEOUT_MS = 8000L
        private const val CONNECT_TIMEOUT_MS = 35000L
        private const val SCAN_MS = 6000L
        private const val POLL_MS = 30000L
        private const val OFFLINE_AFTER_MS = 5 * 60 * 1000L
    }

    private enum class State { STOPPED, IDLE, SCANNING, CONNECTING, PAIRING, CONNECTED }

    private class Op(val name: String, val start: () -> Boolean)
    private class Cmd(val dest: Int, val command: Int, val data: ByteArray)

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager.adapter
    private val random = SecureRandom()

    private var meshName = ByteArray(0)
    private var meshPassword = ByteArray(0)
    private var devices: List<MeshDevice> = emptyList()

    private var state = State.STOPPED
    private var gatt: BluetoothGatt? = null
    private var connectedMac = ""
    private var sessionKey: ByteArray? = null
    private var sessionRandom = ByteArray(8)
    private var statusChar: BluetoothGattCharacteristic? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var pairChar: BluetoothGattCharacteristic? = null

    private val ops = ArrayDeque<Op>()
    private var opBusy = false
    private val commands = ArrayDeque<Cmd>()
    private var lastCommandAt = 0L
    private var pumpScheduled = false

    private var reconnectDelay = 5000L
    private var candidates = ArrayDeque<String>()
    private val scanRssi = HashMap<String, Int>()
    private val lastSeen = HashMap<Int, Long>()
    private val online = HashSet<Int>()
    private var preferredMac: String? = null
    private val attemptsForMac = HashMap<String, Int>()

    val stateName: String get() = when (state) {
        State.CONNECTED -> "verbunden mit $connectedMac"
        State.CONNECTING -> "verbinde mit $connectedMac"
        State.PAIRING -> "Anmeldung am Mesh ($connectedMac)"
        State.SCANNING -> "suche Lampen"
        State.IDLE -> "warte"
        State.STOPPED -> "gestoppt"
    }

    // ---------------------------------------------------------------- lifecycle

    fun start(cfg: Config) {
        handler.post {
            meshName = cfg.meshName.toByteArray()
            meshPassword = cfg.meshPassword.toByteArray()
            devices = cfg.devices
            if (meshName.size > 16 || meshPassword.size > 16) {
                BridgeLog.e("Mesh-Name/Passwort länger als 16 Bytes – ungültig")
                return@post
            }
            BridgeLog.i("Mesh-Bridge startet, ${devices.size} Geräte: " +
                devices.joinToString { "${it.name} [${it.meshId}] ${it.mac}" })
            state = State.IDLE
            setState(State.IDLE)
            beginConnectCycle()
            handler.postDelayed(pollRunnable, POLL_MS)
        }
    }

    fun stop() {
        handler.post {
            handler.removeCallbacks(pollRunnable)
            handler.removeCallbacks(reconnectRunnable)
            state = State.STOPPED
            stopScan()
            closeGatt()
            setState(State.STOPPED)
        }
    }

    fun reconnect() {
        handler.post {
            BridgeLog.i("Neuverbindung angefordert")
            closeGatt()
            reconnectDelay = 2000L
            setState(State.IDLE)
            beginConnectCycle()
        }
    }

    private fun setState(s: State) {
        state = s
        listener.onMeshState(stateName)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (state == State.CONNECTED) {
                queueCommand(0xffff, C_REQUEST_STATUS, byteArrayOf(0x10))
            }
            val now = SystemClock.elapsedRealtime()
            for (id in online.toList()) {
                if (now - (lastSeen[id] ?: 0L) > OFFLINE_AFTER_MS) {
                    online.remove(id)
                    BridgeLog.w("Gerät $id seit 5 Minuten ohne Meldung -> offline")
                    listener.onDeviceOffline(id)
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    // ---------------------------------------------------------------- connect cycle

    private fun hasLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun beginConnectCycle() {
        if (state == State.STOPPED) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            BridgeLog.w("Bluetooth ist aus – neuer Versuch in 15 s")
            scheduleReconnect(15000L)
            return
        }
        if (devices.isEmpty()) {
            BridgeLog.e("Keine Geräte konfiguriert – bitte zuerst aus der AwoX-Cloud importieren")
            return
        }
        if (hasLocationPermission()) startScan() else {
            BridgeLog.w("Keine Standort-Berechtigung: verbinde ohne Scan der Reihe nach")
            buildCandidates()
            tryNextCandidate()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address.uppercase()
            val known = devices.any { it.mac == mac }
            val awox = result.scanRecord?.getManufacturerSpecificData(AWOX_MANUFACTURER_ID) != null
            if (known || awox) {
                handler.post {
                    if (!scanRssi.containsKey(mac)) {
                        BridgeLog.i("Gefunden: $mac RSSI ${result.rssi}${if (known) "" else " (AwoX, nicht in Liste)"}")
                    }
                    scanRssi[mac] = result.rssi
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post { BridgeLog.w("Scan fehlgeschlagen (Code $errorCode)") }
        }
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            buildCandidates(); tryNextCandidate(); return
        }
        scanRssi.clear()
        setState(State.SCANNING)
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, scanCallback)
        } catch (t: Throwable) {
            BridgeLog.e("Scan konnte nicht gestartet werden", t)
            buildCandidates(); tryNextCandidate(); return
        }
        handler.postDelayed({
            stopScan()
            buildCandidates()
            // give the controller a moment after stopping the scan
            handler.postDelayed({ tryNextCandidate() }, 500)
        }, SCAN_MS)
    }

    private fun stopScan() {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Throwable) { }
    }

    private fun buildCandidates() {
        val known = devices.map { it.mac }.distinct()
        val seen = known.filter { scanRssi.containsKey(it) }.sortedByDescending { scanRssi[it] }
        val unseen = known.filter { !scanRssi.containsKey(it) }
            .sortedByDescending { if (it == preferredMac) 1 else 0 }
        candidates = ArrayDeque(seen + unseen)
        attemptsForMac.clear()
        if (scanRssi.isNotEmpty() || hasLocationPermission()) {
            BridgeLog.i("Im Scan gefunden: " + (if (seen.isEmpty()) "keine bekannte Lampe" else
                seen.joinToString { "$it(${scanRssi[it]})" }))
        }
    }

    private fun tryNextCandidate() {
        if (state == State.STOPPED) return
        val mac = candidates.removeFirstOrNull()
        if (mac == null) {
            BridgeLog.w("Keine Lampe erreichbar – neuer Versuch in ${reconnectDelay / 1000} s")
            scheduleReconnect(reconnectDelay)
            reconnectDelay = minOf(reconnectDelay * 2, 120000L)
            return
        }
        connectTo(mac)
    }

    private fun scheduleReconnect(delay: Long) {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    private val reconnectRunnable = Runnable {
        if (state == State.IDLE) beginConnectCycle()
    }

    private val connectTimeout = Runnable {
        if (state == State.CONNECTING || state == State.PAIRING) {
            BridgeLog.w("Zeitüberschreitung bei $connectedMac")
            closeGatt()
            setState(State.IDLE)
            val n = (attemptsForMac[connectedMac] ?: 0) + 1
            attemptsForMac[connectedMac] = n
            if (n < 2 && scanRssi.containsKey(connectedMac)) {
                // lamp is visible but direct connect timed out: try once more in autoConnect mode
                candidates.addFirst(connectedMac)
            }
            handler.postDelayed({ tryNextCandidate() }, 1000)
        }
    }

    private fun connectTo(mac: String) {
        val device: BluetoothDevice = try {
            adapter!!.getRemoteDevice(mac)
        } catch (t: Throwable) {
            BridgeLog.e("Ungültige MAC $mac", t); tryNextCandidate(); return
        }
        connectedMac = mac
        setState(State.CONNECTING)
        val auto = (attemptsForMac[mac] ?: 0) >= 1
        BridgeLog.i("Verbinde mit $mac${if (auto) " (autoConnect)" else ""} …")
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, auto, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, auto, gattCallback)
        }
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private fun closeGatt() {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(opTimeout)
        val g = gatt
        gatt = null
        sessionKey = null
        statusChar = null; commandChar = null; pairChar = null
        ops.clear(); opBusy = false
        if (g != null) {
            try { g.disconnect() } catch (_: Throwable) { }
            try { g.close() } catch (_: Throwable) { }
        }
    }

    private fun onDisconnected(reason: String) {
        val wasConnected = state == State.CONNECTED
        closeGatt()
        if (state == State.STOPPED) return
        setState(State.IDLE)
        if (wasConnected) {
            BridgeLog.w("Verbindung zu $connectedMac getrennt ($reason)")
            reconnectDelay = 3000L
            scheduleReconnect(reconnectDelay)
        } else {
            BridgeLog.w("Verbindung zu $connectedMac fehlgeschlagen ($reason)")
            val n = (attemptsForMac[connectedMac] ?: 0) + 1
            attemptsForMac[connectedMac] = n
            if (n < 2) {
                // Android often reports GATT error 133 on the first attempt – retry the same lamp once
                candidates.addFirst(connectedMac)
            }
            handler.postDelayed({ tryNextCandidate() }, 1500)
        }
    }

    // ---------------------------------------------------------------- GATT op queue

    private val opTimeout = Runnable {
        BridgeLog.w("GATT-Operation hängt – trenne Verbindung")
        onDisconnected("Timeout")
    }

    private fun enqueueOp(name: String, start: () -> Boolean) {
        ops.addLast(Op(name, start))
        runNextOp()
    }

    private fun runNextOp() {
        if (opBusy) return
        val op = ops.removeFirstOrNull() ?: return
        opBusy = true
        val ok = try { op.start() } catch (t: Throwable) { BridgeLog.e("GATT ${op.name}", t); false }
        if (!ok) {
            BridgeLog.w("GATT-Operation ${op.name} konnte nicht gestartet werden")
            opBusy = false
            onDisconnected("${op.name} fehlgeschlagen")
            return
        }
        handler.removeCallbacks(opTimeout)
        handler.postDelayed(opTimeout, OP_TIMEOUT_MS)
    }

    private fun opDone() {
        handler.removeCallbacks(opTimeout)
        opBusy = false
        runNextOp()
    }

    @Suppress("DEPRECATION")
    private fun write(char: BluetoothGattCharacteristic, value: ByteArray, name: String) {
        enqueueOp(name) {
            val g = gatt ?: return@enqueueOp false
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            char.value = value
            g.writeCharacteristic(char)
        }
    }

    private fun read(char: BluetoothGattCharacteristic, name: String) {
        enqueueOp(name) { gatt?.readCharacteristic(char) ?: false }
    }

    // ---------------------------------------------------------------- GATT callback

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g != gatt) { try { g.close() } catch (_: Throwable) { }; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    BridgeLog.i("BLE verbunden, suche Dienste …")
                    handler.postDelayed({ if (g == gatt) g.discoverServices() }, 600)
                } else {
                    onDisconnected("Status $status")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g != gatt) return@post
                val svc = g.getService(SERVICE)
                if (status != BluetoothGatt.GATT_SUCCESS || svc == null) {
                    BridgeLog.w("Telink-Mesh-Dienst nicht gefunden (Status $status)")
                    onDisconnected("kein Mesh-Dienst"); return@post
                }
                statusChar = svc.getCharacteristic(CHAR_STATUS)
                commandChar = svc.getCharacteristic(CHAR_COMMAND)
                pairChar = svc.getCharacteristic(CHAR_PAIR)
                if (statusChar == null || commandChar == null || pairChar == null) {
                    BridgeLog.w("Mesh-Charakteristiken fehlen")
                    onDisconnected("Charakteristik fehlt"); return@post
                }
                setState(State.PAIRING)
                random.nextBytes(sessionRandom)
                val pkt = MeshCrypto.makePairPacket(meshName, meshPassword, sessionRandom)
                write(pairChar!!, pkt, "pair-write")
                read(pairChar!!, "pair-read")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    BridgeLog.w("Schreiben auf ${c.uuid.toString().takeLast(4)} fehlgeschlagen (Status $status)")
                }
                opDone()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") val value = c.value?.copyOf() ?: ByteArray(0)
            handler.post {
                if (g != gatt) return@post
                opDone()
                if (c.uuid == CHAR_PAIR) handlePairReply(status, value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = c.value?.copyOf() ?: ByteArray(0)
            handler.post {
                if (g != gatt) return@post
                if (c.uuid == CHAR_STATUS) handleNotification(value)
            }
        }
    }

    private fun handlePairReply(status: Int, value: ByteArray) {
        if (status != BluetoothGatt.GATT_SUCCESS || value.isEmpty()) {
            onDisconnected("Pair-Antwort fehlt (Status $status)"); return
        }
        when (value[0].toInt() and 0xff) {
            0x0d -> {
                sessionKey = MeshCrypto.makeSessionKey(
                    meshName, meshPassword, sessionRandom, value.copyOfRange(1, minOf(9, value.size))
                )
                BridgeLog.i("Mesh-Anmeldung an $connectedMac erfolgreich")
                val sc = statusChar!!
                gatt?.setCharacteristicNotification(sc, true)
                // AwoX/Telink: notifications are enabled by writing 0x01 to the status characteristic itself
                write(sc, byteArrayOf(0x01), "notify-enable")
                enqueueOp("connected") {
                    handler.post { onPaired() }
                    true
                }
            }
            0x0e -> {
                BridgeLog.e("Mesh-Zugangsdaten werden von $connectedMac abgelehnt (falsches Konto / neu gekoppelt?)")
                onDisconnected("Anmeldung abgelehnt")
            }
            else -> {
                BridgeLog.e("Unerwartete Pair-Antwort: ${MeshCrypto.hex(value)}")
                onDisconnected("unerwartete Pair-Antwort")
            }
        }
    }

    private fun onPaired() {
        debugPackets = 0
        BridgeLog.i("Geräte: " + devices.joinToString { "${it.name} [${it.meshId}] ${it.mac}" })
        // the pseudo-op "connected" has no callback; release the queue
        opDone()
        handler.removeCallbacks(connectTimeout)
        preferredMac = connectedMac
        reconnectDelay = 5000L
        setState(State.CONNECTED)
        commands.addFirst(Cmd(0xffff, C_REQUEST_STATUS, byteArrayOf(0x10)))
        for (d in devices) commands.addLast(Cmd(d.meshId, C_REQUEST_STATUS, byteArrayOf(0x10)))
        pumpCommands()
    }

    // ---------------------------------------------------------------- notifications

    private fun u(b: Byte) = b.toInt() and 0xff

    private var debugPackets = 0

    private fun handleNotification(raw: ByteArray) {
        val key = sessionKey ?: return
        if (raw.size < 20) {
            BridgeLog.w("Kurze Notification (${raw.size} B): ${MeshCrypto.hex(raw)}")
            return
        }
        val p = MeshCrypto.decryptPacket(key, connectedMac, raw)
        val cmd = u(p[7])
        if (debugPackets < 60) {
            debugPackets++
            BridgeLog.i("RX %02x src=%d: %s".format(cmd, u(p[4]) * 256 + u(p[3]), MeshCrypto.hex(p)))
        }
        val status = when (cmd) {
            R_ONLINE_STATUS -> {
                val mode = u(p[12])
                LightStatus(
                    meshId = u(p[19]) * 256 + u(p[10]),
                    online = u(p[11]) != 0,
                    on = (mode and 1) == 1,
                    colorMode = ((mode shr 1) and 1) == 1,
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
                    whiteBrightness = u(p[11]), temperature = u(p[12]), colorBrightness = u(p[13]),
                    r = u(p[14]), g = u(p[15]), b = u(p[16])
                )
            }
            else -> null
        }
        if (status == null) return
        if (devices.none { it.meshId == status.meshId }) {
            BridgeLog.w("Meldung von unbekannter Mesh-ID ${status.meshId} ignoriert")
            return
        }

        if (status.online) {
            lastSeen[status.meshId] = SystemClock.elapsedRealtime()
            if (online.add(status.meshId)) BridgeLog.i("Gerät ${status.meshId} online")
            listener.onStatus(status)
        } else {
            if (online.remove(status.meshId)) BridgeLog.w("Gerät ${status.meshId} meldet offline")
            listener.onDeviceOffline(status.meshId)
        }
    }

    // ---------------------------------------------------------------- commands

    fun setPower(dest: Int, on: Boolean) = queueCommand(dest, C_POWER, byteArrayOf(if (on) 1 else 0, 0, 0))
    fun setColor(dest: Int, r: Int, g: Int, b: Int) =
        queueCommand(dest, C_COLOR, byteArrayOf(0x04, r.toByte(), g.toByte(), b.toByte()))
    fun setColorBrightness(dest: Int, v: Int) = queueCommand(dest, C_COLOR_BRIGHTNESS, byteArrayOf(v.toByte()))
    fun setWhiteBrightness(dest: Int, v: Int) = queueCommand(dest, C_WHITE_BRIGHTNESS, byteArrayOf(v.toByte()))
    fun setWhiteTemperature(dest: Int, v: Int) = queueCommand(dest, C_WHITE_TEMPERATURE, byteArrayOf(v.toByte()))

    fun queueCommand(dest: Int, command: Int, data: ByteArray) {
        handler.post {
            if (state != State.CONNECTED) {
                BridgeLog.w("Nicht verbunden – Befehl %02x für %d wird nachgeholt".format(command, dest))
            }
            commands.addLast(Cmd(dest, command, data))
            while (commands.size > 30) commands.removeFirst()
            pumpCommands()
        }
    }

    private fun pumpCommands() {
        if (state != State.CONNECTED || pumpScheduled) return
        val wait = lastCommandAt + COMMAND_SPACING_MS - SystemClock.elapsedRealtime()
        if (wait > 0) {
            pumpScheduled = true
            handler.postDelayed({ pumpScheduled = false; pumpCommands() }, wait)
            return
        }
        val cmd = commands.removeFirstOrNull() ?: return
        val key = sessionKey ?: return
        val char = commandChar ?: return
        val seq = ByteArray(3).also { random.nextBytes(it) }
        val pkt = MeshCrypto.makeCommandPacket(key, connectedMac, cmd.dest, cmd.command, cmd.data, seq)
        lastCommandAt = SystemClock.elapsedRealtime()
        BridgeLog.i("TX %02x an %d: %s".format(cmd.command, cmd.dest, MeshCrypto.hex(cmd.data)))
        write(char, pkt, "cmd-%02x".format(cmd.command))
        if (commands.isNotEmpty()) {
            pumpScheduled = true
            handler.postDelayed({ pumpScheduled = false; pumpCommands() }, COMMAND_SPACING_MS)
        }
    }
}
