package de.eglobridge.esphome

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

/** Identity reported to Home Assistant (DeviceInfoResponse / HelloResponse). */
data class DeviceIdentity(
    /** Hostname-like node name (lowercase, a-z0-9 and '-'), max 31 characters. */
    val name: String,
    val friendlyName: String,
    /** "AA:BB:CC:DD:EE:FF" – Home Assistant uses it as the unique id of the device. */
    val mac: String,
    val model: String,
    val manufacturer: String,
    /** App version (shown as firmware version in Home Assistant). */
    val projectVersion: String,
    /** Home Assistant splits this at the dot into manufacturer and model. */
    val projectName: String = "EGLO Bridge.Sprach-Satellit",
    /** ESPHome-style version; Home Assistant parses it, so keep the YYYY.M.P format. */
    val esphomeVersion: String = "2026.11.0",
    val suggestedArea: String = "",
)

data class WakeWord(val id: String, val wakeWord: String, val languages: List<String> = emptyList())

data class VoiceConfig(
    val available: List<WakeWord>,
    val active: List<String>,
    val maxActive: Int,
)

data class MediaFormat(val format: String, val sampleRate: Int, val channels: Int, val purpose: Int, val sampleBytes: Int = 0)

/**
 * The tablet side of the API. All callbacks arrive on the connection's reader thread; implementations
 * must not block for long.
 */
interface ApiDevice {
    fun identity(): DeviceIdentity
    fun voiceFeatureFlags(): Int

    // entities
    fun mediaPlayerFormats(): List<MediaFormat>
    fun mediaPlayerFeatures(): Int
    /** state (Va.MP_*), volume 0..1, muted */
    fun mediaPlayerState(): Triple<Int, Float, Boolean>
    fun micMuted(): Boolean

    fun onMediaPlayerCommand(command: Int?, volume: Float?, url: String?, announcement: Boolean)
    fun onMicMuteCommand(muted: Boolean)

    // voice assistant
    fun onVoiceSubscription(subscribed: Boolean)
    fun onVoiceResponse(port: Int, error: Boolean)
    fun onVoiceEvent(type: Int, data: Map<String, String>)
    fun onVoiceAudio(data: ByteArray, end: Boolean) {}
    fun onTimerEvent(type: Int, id: String, name: String, totalSeconds: Int, secondsLeft: Int, active: Boolean)
    fun onAnnounce(mediaId: String, text: String, preannounceMediaId: String, startConversation: Boolean)
    fun voiceConfiguration(): VoiceConfig
    fun onSetActiveWakeWords(ids: List<String>)

    fun onClientCountChanged(count: Int) {}
}

/**
 * ESPHome native API server (plaintext transport), so Home Assistant's ESPHome integration can
 * adopt the tablet like an ESPHome voice satellite (Voice PE style): assist satellite, media player
 * and a microphone mute switch. Pure JVM, no Android dependencies, so it can be unit tested.
 */
class ApiServer(
    private val device: ApiDevice,
    private val port: Int = DEFAULT_PORT,
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val DEFAULT_PORT = 6053
        const val API_MAJOR = 1
        const val API_MINOR = 13
        const val SERVER_INFO = "eglo-ble-bridge (ESPHome API)"

        const val MEDIA_PLAYER_OBJECT_ID = "media_player"
        const val MUTE_OBJECT_ID = "mute"
        val MEDIA_PLAYER_KEY = fnv1Hash(MEDIA_PLAYER_OBJECT_ID)
        val MUTE_KEY = fnv1Hash(MUTE_OBJECT_ID)

        /** Same object-id hash ESPHome uses for entity keys. */
        fun fnv1Hash(s: String): Int {
            var h = 2166136261L.toInt()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                h *= 16777619
                h = h xor (b.toInt() and 0xFF)
            }
            return h
        }
    }

    @Volatile private var server: ServerSocket? = null
    private val connections = CopyOnWriteArrayList<Connection>()
    /** The connection that subscribed to the voice assistant (Home Assistant). */
    @Volatile private var voiceConn: Connection? = null

    val isRunning get() = server != null
    val clientCount get() = connections.count { it.authenticated }
    val voiceSubscribed get() = voiceConn != null

    fun start() {
        if (server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(java.net.InetSocketAddress(port))
        server = s
        Thread({ acceptLoop(s) }, "esphome-api").apply { isDaemon = true; start() }
        Thread({ keepAliveLoop(s) }, "esphome-keepalive").apply { isDaemon = true; start() }
        log("ESPHome-API lauscht auf Port $port")
    }

    fun stop() {
        val s = server ?: return
        server = null
        try { s.close() } catch (_: IOException) { }
        connections.forEach { it.close() }
        connections.clear()
        voiceConn = null
    }

    /**
     * Detects dead connections. While audio streams, Home Assistant only receives and may never ping
     * us, so we ping it after 45 s of silence; nothing at all for 100 s means the connection is gone
     * (e.g. Wi-Fi dropped, Home Assistant restarted) – close it so a new connection works cleanly.
     */
    private fun keepAliveLoop(s: ServerSocket) {
        while (server === s) {
            try { Thread.sleep(15_000) } catch (_: InterruptedException) { return }
            val now = System.currentTimeMillis()
            for (c in connections) {
                val idle = now - c.lastRx
                if (idle > 100_000) {
                    log("ESPHome-API: keine Antwort seit ${idle / 1000} s – Verbindung wird geschlossen")
                    c.close()
                } else if (idle > 45_000 && c.authenticated) {
                    c.send(Msg.PING_REQUEST, ProtoWriter())
                }
            }
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (server === s) {
            val sock = try { s.accept() } catch (_: IOException) { break }
            val c = Connection(sock)
            connections.add(c)
            Thread({ c.run() }, "esphome-conn").apply { isDaemon = true; start() }
        }
    }

    // ------------------------------------------------------------------ device -> Home Assistant

    /** Ask Home Assistant to start (or stop) a pipeline run. Returns false if HA is not subscribed. */
    fun sendVoiceRequest(
        start: Boolean,
        conversationId: String = "",
        flags: Int = 0,
        wakeWordPhrase: String = "",
        noiseSuppression: Int = 0,
        autoGain: Int = 0,
        volumeMultiplier: Float = 1f,
    ): Boolean {
        val c = voiceConn ?: return false
        val w = ProtoWriter()
            .bool(1, start)
            .string(2, conversationId)
            .uint(3, flags)
        if (start) {
            w.message(4, ProtoWriter().uint(1, noiseSuppression).uint(2, autoGain).float(3, volumeMultiplier))
            w.string(5, wakeWordPhrase)
        }
        return c.send(Msg.VOICE_ASSISTANT_REQUEST, w)
    }

    fun sendVoiceAudio(pcm: ByteArray, end: Boolean = false): Boolean {
        val c = voiceConn ?: return false
        return c.send(Msg.VOICE_ASSISTANT_AUDIO, ProtoWriter().bytes(1, pcm).bool(2, end))
    }

    fun sendAnnounceFinished(success: Boolean): Boolean {
        val c = voiceConn ?: return false
        return c.send(Msg.VOICE_ASSISTANT_ANNOUNCE_FINISHED, ProtoWriter().bool(1, success))
    }

    /** Push the current entity states to every client that subscribed to states. */
    fun pushStates() {
        for (c in connections) if (c.statesSubscribed) c.sendStates()
    }

    // ------------------------------------------------------------------ connection

    private inner class Connection(private val sock: Socket) {
        private val input: InputStream = BufferedInputStream(sock.getInputStream())
        private val output: OutputStream = sock.getOutputStream()
        @Volatile var authenticated = false
        @Volatile var statesSubscribed = false
        @Volatile private var closed = false
        /** Time of the last frame from the client (System.currentTimeMillis()). */
        @Volatile var lastRx = System.currentTimeMillis()
        private val peer = sock.remoteSocketAddress?.toString() ?: "?"

        fun run() {
            try {
                sock.tcpNoDelay = true
                sock.soTimeout = 0
                while (!closed) {
                    val frame = readFrame() ?: break
                    lastRx = System.currentTimeMillis()
                    handle(frame.first, ProtoMessage.parse(frame.second))
                }
            } catch (e: SocketException) {
                // closed
            } catch (e: Exception) {
                log("ESPHome-API: Verbindung $peer beendet: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            try { sock.close() } catch (_: IOException) { }
            connections.remove(this)
            if (voiceConn === this) {
                voiceConn = null
                device.onVoiceSubscription(false)
            }
            if (authenticated) {
                log("ESPHome-API: Home Assistant getrennt ($peer)")
                device.onClientCountChanged(clientCount)
            }
        }

        private fun readVarint(): Long? {
            var shift = 0
            var result = 0L
            while (true) {
                val b = input.read()
                if (b < 0) return null
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 35) throw IOException("varint too long")
            }
        }

        /** Plaintext frame: 0x00, varint length, varint type, payload. */
        private fun readFrame(): Pair<Int, ByteArray>? {
            val pre = input.read()
            if (pre < 0) return null
            if (pre != 0x00) {
                if (pre == 0x01) {
                    // Noise handshake: tell the client we only speak plaintext. Sending the plaintext
                    // marker makes aioesphomeapi report "requires plaintext" instead of hanging.
                    log("ESPHome-API: Client wollte Verschlüsselung (noch nicht unterstützt) – bitte in HA ohne Schlüssel einrichten")
                }
                throw IOException("unexpected preamble $pre")
            }
            val len = readVarint() ?: return null
            val type = readVarint() ?: return null
            if (len > 1_000_000) throw IOException("frame too large: $len")
            val data = ByteArray(len.toInt())
            var off = 0
            while (off < data.size) {
                val n = input.read(data, off, data.size - off)
                if (n < 0) return null
                off += n
            }
            return type.toInt() to data
        }

        fun send(type: Int, w: ProtoWriter): Boolean = sendRaw(type, w.bytes())

        @Synchronized
        fun sendRaw(type: Int, payload: ByteArray): Boolean {
            if (closed) return false
            return try {
                val b = ByteArrayOutputStream(payload.size + 8)
                b.write(0)
                b.write(ProtoWriter.varint(payload.size.toLong()))
                b.write(ProtoWriter.varint(type.toLong()))
                b.write(payload)
                output.write(b.toByteArray())
                output.flush()
                true
            } catch (e: IOException) {
                close(); false
            }
        }

        private fun handle(type: Int, m: ProtoMessage) {
            if (!authenticated && type !in setOf(Msg.HELLO_REQUEST, Msg.DEVICE_INFO_REQUEST, Msg.PING_REQUEST, Msg.DISCONNECT_REQUEST, 3)) {
                // ESPHome closes connections that skip the hello
                throw IOException("message $type before hello")
            }
            when (type) {
                Msg.HELLO_REQUEST -> {
                    val id = device.identity()
                    send(Msg.HELLO_RESPONSE, ProtoWriter()
                        .uint(1, API_MAJOR).uint(2, API_MINOR)
                        .string(3, SERVER_INFO).string(4, id.name))
                    authenticated = true
                    log("ESPHome-API: verbunden mit ${m.string(1).ifEmpty { "Client" }} ($peer)")
                    device.onClientCountChanged(clientCount)
                }
                3 -> { /* AuthenticationRequest (deprecated, no password): no answer */ }
                Msg.DISCONNECT_REQUEST -> {
                    send(Msg.DISCONNECT_RESPONSE, ProtoWriter())
                    close()
                }
                Msg.DISCONNECT_RESPONSE -> close()
                Msg.PING_REQUEST -> send(Msg.PING_RESPONSE, ProtoWriter())
                Msg.PING_RESPONSE -> {}
                Msg.DEVICE_INFO_REQUEST -> sendDeviceInfo()
                Msg.LIST_ENTITIES_REQUEST -> sendEntities()
                Msg.SUBSCRIBE_STATES_REQUEST -> { statesSubscribed = true; sendStates() }
                Msg.SUBSCRIBE_LOGS_REQUEST, Msg.SUBSCRIBE_HA_SERVICES, Msg.SUBSCRIBE_HA_STATES,
                Msg.GET_TIME_RESPONSE -> {}
                Msg.GET_TIME_REQUEST -> send(Msg.GET_TIME_RESPONSE, ProtoWriter().fixed32(1, (System.currentTimeMillis() / 1000).toInt()))
                Msg.SWITCH_COMMAND -> if (m.int(1) == MUTE_KEY) device.onMicMuteCommand(m.bool(2))
                Msg.MEDIA_PLAYER_COMMAND -> if (m.int(1) == MEDIA_PLAYER_KEY) {
                    device.onMediaPlayerCommand(
                        command = if (m.bool(2)) m.int(3) else null,
                        volume = if (m.bool(4)) m.float(5) else null,
                        url = if (m.bool(6)) m.string(7) else null,
                        announcement = m.bool(8) && m.bool(9),
                    )
                }
                Msg.SUBSCRIBE_VOICE_ASSISTANT -> {
                    if (m.bool(1)) {
                        val old = voiceConn
                        voiceConn = this
                        if (old !== this) device.onVoiceSubscription(true)
                    } else if (voiceConn === this) {
                        voiceConn = null
                        device.onVoiceSubscription(false)
                    }
                }
                Msg.VOICE_ASSISTANT_RESPONSE -> device.onVoiceResponse(m.int(1), m.bool(2))
                Msg.VOICE_ASSISTANT_EVENT -> {
                    val data = LinkedHashMap<String, String>()
                    for (d in m.messages(2)) data[d.string(1)] = d.string(2)
                    device.onVoiceEvent(m.int(1), data)
                }
                Msg.VOICE_ASSISTANT_AUDIO -> device.onVoiceAudio(m.bytes(1), m.bool(2))
                Msg.VOICE_ASSISTANT_TIMER_EVENT -> device.onTimerEvent(
                    m.int(1), m.string(2), m.string(3), m.int(4), m.int(5), m.bool(6)
                )
                Msg.VOICE_ASSISTANT_ANNOUNCE_REQUEST -> device.onAnnounce(
                    m.string(1), m.string(2), m.string(3), m.bool(4)
                )
                Msg.VOICE_ASSISTANT_CONFIGURATION_REQUEST -> sendVoiceConfig()
                Msg.VOICE_ASSISTANT_SET_CONFIGURATION -> device.onSetActiveWakeWords(m.strings(1))
                else -> { /* unsupported message: ignore like ESPHome does */ }
            }
        }

        private fun sendDeviceInfo() {
            val id = device.identity()
            val w = ProtoWriter()
                .string(2, id.name)
                .string(3, id.mac)
                .string(4, id.esphomeVersion)
                .string(5, "")
                .string(6, id.model)
                .string(8, id.projectName)
                .string(9, id.projectVersion)
                .string(12, id.manufacturer)
                .string(13, id.friendlyName)
                .string(16, id.suggestedArea)
                .uint(17, device.voiceFeatureFlags())
            send(Msg.DEVICE_INFO_RESPONSE, w)
        }

        private fun sendEntities() {
            val mp = ProtoWriter()
                .string(1, MEDIA_PLAYER_OBJECT_ID)
                .fixed32(2, MEDIA_PLAYER_KEY, force = true)
                .string(3, "Media Player")
                .string(5, "mdi:speaker")
            for (f in device.mediaPlayerFormats()) {
                mp.message(9, ProtoWriter()
                    .string(1, f.format).uint(2, f.sampleRate).uint(3, f.channels)
                    .uint(4, f.purpose).uint(5, f.sampleBytes))
            }
            mp.uint(11, device.mediaPlayerFeatures())
            send(Msg.LIST_ENTITIES_MEDIA_PLAYER, mp)

            send(Msg.LIST_ENTITIES_SWITCH, ProtoWriter()
                .string(1, MUTE_OBJECT_ID)
                .fixed32(2, MUTE_KEY, force = true)
                .string(3, "Mikrofon stumm")
                .string(5, "mdi:microphone-off")
                .uint(8, 1 /* ENTITY_CATEGORY_CONFIG */))

            send(Msg.LIST_ENTITIES_DONE, ProtoWriter())
        }

        fun sendStates() {
            val (state, volume, muted) = device.mediaPlayerState()
            send(Msg.MEDIA_PLAYER_STATE, ProtoWriter()
                .fixed32(1, MEDIA_PLAYER_KEY, force = true)
                .uint(2, state).float(3, volume).bool(4, muted))
            send(Msg.SWITCH_STATE, ProtoWriter()
                .fixed32(1, MUTE_KEY, force = true)
                .bool(2, device.micMuted()))
        }

        private fun sendVoiceConfig() {
            val cfg = device.voiceConfiguration()
            val w = ProtoWriter()
            for (ww in cfg.available) {
                val m = ProtoWriter().string(1, ww.id).string(2, ww.wakeWord)
                for (l in ww.languages) m.string(3, l, force = true)
                w.message(1, m)
            }
            for (a in cfg.active) w.string(2, a, force = true)
            w.uint(3, cfg.maxActive)
            send(Msg.VOICE_ASSISTANT_CONFIGURATION_RESPONSE, w)
        }
    }
}
