package de.eglobridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import java.security.SecureRandom
import java.util.UUID

/**
 * One authenticated BLE connection to a single lamp of the Telink/AwoX mesh.
 * Owns its GATT op queue and its command queue. All methods must be called on
 * the handler thread; GATT callbacks are re-posted to it.
 */
@SuppressLint("MissingPermission")
class MeshConnection(
    private val context: Context,
    private val handler: Handler,
    val mac: String,
    private val meshName: ByteArray,
    private val meshPassword: ByteArray,
    private val autoConnect: Boolean,
    private val events: Events
) {
    interface Events {
        fun onPaired(c: MeshConnection)
        fun onClosed(c: MeshConnection, wasPaired: Boolean, reason: String)
        fun onPacket(c: MeshConnection, packet: ByteArray)
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1910")
        val CHAR_STATUS: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1911")
        val CHAR_COMMAND: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1912")
        val CHAR_PAIR: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1914")

        private const val COMMAND_SPACING_MS = 180L
        private const val OP_TIMEOUT_MS = 8000L
        private const val CONNECT_TIMEOUT_MS = 35000L
        private const val MAX_QUEUE = 40
    }

    private enum class State { NEW, CONNECTING, PAIRING, PAIRED, CLOSED }

    private class Op(val name: String, val start: () -> Boolean)
    class Cmd(val dest: Int, val command: Int, val data: ByteArray, val coalesce: Boolean)

    private val random = SecureRandom()
    private var state = State.NEW
    private var gatt: BluetoothGatt? = null
    private var sessionKey: ByteArray? = null
    private val sessionRandom = ByteArray(8)
    private var statusChar: BluetoothGattCharacteristic? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var pairChar: BluetoothGattCharacteristic? = null

    private val ops = ArrayDeque<Op>()
    private var opBusy = false
    private val commands = ArrayDeque<Cmd>()
    private var lastCommandAt = 0L
    private var pumpScheduled = false

    val isPaired get() = state == State.PAIRED
    val isClosed get() = state == State.CLOSED

    // ------------------------------------------------------------------ lifecycle

    fun connect(device: BluetoothDevice) {
        state = State.CONNECTING
        BridgeLog.i("Verbinde mit $mac${if (autoConnect) " (autoConnect)" else ""} …")
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, autoConnect, callback)
        }
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    fun close(reason: String) {
        if (state == State.CLOSED) return
        val wasPaired = state == State.PAIRED
        state = State.CLOSED
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(opTimeout)
        handler.removeCallbacks(pumpRunnable)
        val g = gatt
        gatt = null
        sessionKey = null
        ops.clear(); commands.clear(); opBusy = false
        if (g != null) {
            try { g.disconnect() } catch (_: Throwable) { }
            try { g.close() } catch (_: Throwable) { }
        }
        events.onClosed(this, wasPaired, reason)
    }

    private val connectTimeout = Runnable {
        if (state == State.CONNECTING || state == State.PAIRING) close("Zeitüberschreitung")
    }

    // ------------------------------------------------------------------ GATT op queue

    private val opTimeout = Runnable { close("GATT-Operation hängt") }

    private fun enqueueOp(name: String, start: () -> Boolean) {
        ops.addLast(Op(name, start))
        runNextOp()
    }

    private fun runNextOp() {
        if (opBusy || state == State.CLOSED) return
        val op = ops.removeFirstOrNull() ?: return
        opBusy = true
        val ok = try { op.start() } catch (t: Throwable) { BridgeLog.e("GATT ${op.name}", t); false }
        if (!ok) {
            opBusy = false
            close("${op.name} fehlgeschlagen")
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

    // ------------------------------------------------------------------ GATT callback

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g != gatt) { try { g.close() } catch (_: Throwable) { }; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    BridgeLog.i("BLE verbunden mit $mac, suche Dienste …")
                    handler.postDelayed({ if (g == gatt) g.discoverServices() }, 600)
                } else {
                    close("Status $status")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g != gatt) return@post
                val svc = g.getService(SERVICE)
                if (status != BluetoothGatt.GATT_SUCCESS || svc == null) {
                    close("kein Mesh-Dienst (Status $status)"); return@post
                }
                statusChar = svc.getCharacteristic(CHAR_STATUS)
                commandChar = svc.getCharacteristic(CHAR_COMMAND)
                pairChar = svc.getCharacteristic(CHAR_PAIR)
                val pc = pairChar
                if (statusChar == null || commandChar == null || pc == null) {
                    close("Mesh-Charakteristik fehlt"); return@post
                }
                state = State.PAIRING
                random.nextBytes(sessionRandom)
                write(pc, MeshCrypto.makePairPacket(meshName, meshPassword, sessionRandom), "pair-write")
                read(pc, "pair-read")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    BridgeLog.w("$mac: Schreiben fehlgeschlagen (Status $status)")
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
                if (c.uuid != CHAR_STATUS) return@post
                val key = sessionKey ?: return@post
                if (value.size < 20) return@post
                events.onPacket(this@MeshConnection, MeshCrypto.decryptPacket(key, mac, value))
            }
        }
    }

    private fun handlePairReply(status: Int, value: ByteArray) {
        if (status != BluetoothGatt.GATT_SUCCESS || value.isEmpty()) {
            close("Pair-Antwort fehlt (Status $status)"); return
        }
        when (value[0].toInt() and 0xff) {
            0x0d -> {
                sessionKey = MeshCrypto.makeSessionKey(
                    meshName, meshPassword, sessionRandom, value.copyOfRange(1, minOf(9, value.size))
                )
                val sc = statusChar ?: run { close("Status-Charakteristik fehlt"); return }
                gatt?.setCharacteristicNotification(sc, true)
                // AwoX/Telink: notifications are enabled by writing 0x01 to the status characteristic itself
                write(sc, byteArrayOf(0x01), "notify-enable")
                enqueueOp("paired") {
                    handler.post {
                        opDone()
                        handler.removeCallbacks(connectTimeout)
                        state = State.PAIRED
                        BridgeLog.i("Mesh-Anmeldung an $mac erfolgreich")
                        events.onPaired(this)
                        pump()
                    }
                    true
                }
            }
            0x0e -> close("Mesh-Zugangsdaten abgelehnt (anderes Konto / neu gekoppelt?)")
            else -> close("unerwartete Pair-Antwort ${MeshCrypto.hex(value)}")
        }
    }

    // ------------------------------------------------------------------ commands

    /**
     * Queue a command. With [coalesce] an already queued command with the same
     * destination and command code is replaced (used for sliders / music mode).
     */
    fun send(dest: Int, command: Int, data: ByteArray, coalesce: Boolean = false) {
        if (state == State.CLOSED) return
        val cmd = Cmd(dest, command, data, coalesce)
        if (coalesce) {
            val idx = commands.indexOfFirst { it.coalesce && it.dest == dest && it.command == command }
            if (idx >= 0) { commands[idx] = cmd; return }
        }
        commands.addLast(cmd)
        while (commands.size > MAX_QUEUE) commands.removeFirst()
        pump()
    }

    fun sendFirst(dest: Int, command: Int, data: ByteArray) {
        commands.addFirst(Cmd(dest, command, data, false))
        pump()
    }

    private val pumpRunnable = Runnable { pumpScheduled = false; pump() }

    private fun pump() {
        if (state != State.PAIRED || pumpScheduled || commands.isEmpty()) return
        val wait = lastCommandAt + COMMAND_SPACING_MS - SystemClock.elapsedRealtime()
        if (wait > 0 || opBusy) {
            pumpScheduled = true
            handler.postDelayed(pumpRunnable, if (wait > 0) wait else 30)
            return
        }
        val cmd = commands.removeFirst()
        val key = sessionKey ?: return
        val char = commandChar ?: return
        val seq = ByteArray(3).also { random.nextBytes(it) }
        val pkt = MeshCrypto.makeCommandPacket(key, mac, cmd.dest, cmd.command, cmd.data, seq)
        lastCommandAt = SystemClock.elapsedRealtime()
        if (!cmd.coalesce || BridgeLog.verbose) {
            BridgeLog.i("TX %02x an %d via %s: %s".format(cmd.command, cmd.dest, mac.takeLast(5), MeshCrypto.hex(cmd.data)))
        }
        write(char, pkt, "cmd-%02x".format(cmd.command))
        if (commands.isNotEmpty()) {
            pumpScheduled = true
            handler.postDelayed(pumpRunnable, COMMAND_SPACING_MS)
        }
    }
}
