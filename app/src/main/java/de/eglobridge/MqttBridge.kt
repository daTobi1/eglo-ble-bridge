package de.eglobridge

import android.os.Handler
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * MQTT side of a bridge, including coordination between several bridges (tablets):
 *
 * - every bridge publishes how well it reaches each lamp (link score);
 *   the bridge with the best score "owns" the lamp: it executes commands and publishes its state
 * - lamps, music mode, music source, effect tempo and the per-lamp music selection are shared
 *   entities in Home Assistant; per-bridge entities (status, log, debug, reconnect) exist per tablet
 * - the selected music-source tablet listens with its microphone and publishes music frames;
 *   every bridge applies them to the lamps it owns
 */
class MqttBridge(
    private val handler: Handler,
    private val mesh: MeshManager,
    private val lights: LightController,
    val version: String,
    val bridgeId: String,
    val bridgeName: String
) {
    companion object {
        const val BASE = "eglo_bridge"
        const val MUSIC = "$BASE/music"
        const val MUSIC_SET = "$BASE/music/set"
        const val MUSIC_FRAME = "$BASE/music/frame"
        const val SOURCE = "$BASE/music/source"
        const val SOURCE_NAME = "$BASE/music/source_name"
        const val SOURCE_SET = "$BASE/music/source/set"
        const val SENS = "$BASE/music_sensitivity"
        const val SENS_SET = "$BASE/music_sensitivity/set"
        const val SPEED = "$BASE/effect_speed"
        const val SPEED_SET = "$BASE/effect_speed/set"
        const val FIRE_SPEED = "$BASE/fire_speed"
        const val FIRE_STRENGTH = "$BASE/fire_strength"
        const val FIRE_BRIGHT = "$BASE/fire_brightness"

        private const val RECOMPUTE_MS = 10000L

        /** Per-lamp effect settings (key, HA name, icon, min). */
        val LAMP_SETTINGS = listOf(
            arrayOf("fire_speed", "Lagerfeuer-Tempo", "mdi:fire", "0"),
            arrayOf("fire_strength", "Lagerfeuer-Stärke", "mdi:fire-alert", "0"),
            arrayOf("fire_brightness", "Lagerfeuer-Helligkeit", "mdi:brightness-6", "5"),
            arrayOf("effect_speed", "Effekt-Tempo", "mdi:speedometer", "0")
        )
        val LAMP_SETTING_KEYS = LAMP_SETTINGS.map { it[0] }

        fun slug(name: String): String {
            val s = name.lowercase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace(Regex("[^a-z0-9]+"), "_").trim('_')
            return s.ifEmpty { "tablet" }
        }

        /** True if a broker accepts these credentials (blocking, a few seconds at most). */
        fun testConnection(host: String, port: Int, user: String, password: String): Boolean = try {
            val c = MqttAsyncClient("tcp://$host:$port", "eglo-bridge-test-" + System.currentTimeMillis() % 100000, MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 8
                if (user.isNotEmpty()) { userName = user; this.password = password.toCharArray() }
            }
            c.connect(opts).waitForCompletion(10000)
            val ok = c.isConnected
            try { c.disconnect().waitForCompletion(2000) } catch (_: Throwable) { }
            try { c.close() } catch (_: Throwable) { }
            ok
        } catch (_: Throwable) { false }
    }

    /** [mic] = bridge can listen; the Raspberry Pi port announces "mic": false without a microphone. */
    private class BridgeInfo(var name: String, var online: Boolean, var mic: Boolean = true)

    // per-bridge topics
    private val bTopic = "$BASE/bridge/$bridgeId"
    private val avail = "$bTopic/status"
    private val info = "$bTopic/info"
    private val stateText = "$bTopic/state"
    private val logTopic = "$bTopic/log"
    private val cmdTopic = "$bTopic/cmd"
    private val debugTopic = "$bTopic/debug"
    private val debugSet = "$bTopic/debug/set"
    private val updateTopic = "$bTopic/update"
    private val updateInstall = "$bTopic/update/install"
    private val micTopic = "$bTopic/mic"
    /** Which lamps this bridge is responsible for (per-bridge switches in HA). */
    private val lampAssign = "$bTopic/lamp"
    private val allAssign = "$bTopic/lamps_all"

    /** Called (on the handler thread) after the lamp assignment changed, e.g. to refresh the app UI. */
    @Volatile var onAssignmentChanged: (() -> Unit)? = null

    /** Commands for the service: restart, reimport, check_update, install_update. */
    @Volatile var onServiceCommand: ((String) -> Unit)? = null
    /** Settings changed remotely (JSON from `<bridge>/config/set`). */
    @Volatile var onConfigSet: ((JSONObject) -> Unit)? = null
    /** Facts about the tablet (model, Android, WebView, IP …) for the retained config topic. */
    @Volatile var deviceInfo: () -> JSONObject = { JSONObject() }
    private val configTopic = "$bTopic/config"
    private val configSet = "$bTopic/config/set"
    @Volatile var hasMicPermission: () -> Boolean = { true }
    private var micState = ""
    private val screenTopic = "$bTopic/screen"
    private val brightTopic = "$bTopic/brightness"
    private val timeoutTopic = "$bTopic/screen_timeout"
    private val urlTopic = "$bTopic/url"
    private val batteryTopic = "$bTopic/battery"
    private val chargingTopic = "$bTopic/charging"
    private val kioskTopic = "$bTopic/kiosk"
    private var config: Config? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var client: MqttAsyncClient? = null
    private var devices: List<MeshDevice> = emptyList()
    private var bridgeState = "startet"
    private var running = false

    // shared knowledge (bridge thread only)
    private val bridges = ConcurrentHashMap<String, BridgeInfo>()
    private val links = ConcurrentHashMap<Int, ConcurrentHashMap<String, Int>>()
    private val myLinks = ConcurrentHashMap<Int, Int>()
    private val published = HashMap<Int, Int>()
    private val remoteStates = ConcurrentHashMap<Int, LightController.LightState>()
    private val lampAvail = ConcurrentHashMap<Int, Boolean>()
    private val selection = ConcurrentHashMap<Int, Boolean>()
    private var musicOn = false
    private var musicSourceId: String? = null
    @Volatile var sensitivityPct = 100; private set
    @Volatile var speed = 50; private set
    private var lastSourceOptions = ""
    private var speedKnown = false
    private val fireKnown = HashSet<String>()
    private val lampKnown = HashSet<String>()
    /** Favourites per lamp: "<id>/1" -> HA light command JSON (shared, retained). */
    private val favorites = ConcurrentHashMap<String, JSONObject>()

    val connected: Boolean get() = client?.isConnected == true

    /** Last payload of every topic this bridge receives (for the MCP endpoint, see McpServer). */
    private val topicCache = ConcurrentHashMap<String, String>()
    fun topics(): Map<String, String> = HashMap(topicCache)
    /** Lamps known to this bridge (same AwoX account on every bridge). */
    fun knownDevices(): List<MeshDevice> = devices
    /** Publish on behalf of the MCP endpoint (same broker login as the bridge). */
    fun send(topic: String, payload: String, retain: Boolean = false) = publish(topic, payload, retain, 1)

    // ================================================================= lifecycle

    fun start(cfg: Config) {
        config = cfg
        KioskControl.listeners.add(kioskListener)
        devices = cfg.devices
        running = true
        bridges[bridgeId] = BridgeInfo(bridgeName, true)
        lights.stateListeners.add { id, st -> handler.post { onLocalState(id, st) } }
        lights.availabilityListeners.add { _, _ -> handler.post { recompute() } }
        lights.onMusicRequested = { handler.post { requestMusic(true) } }
        lights.onMicFrame = { r, g, b, bright, beat ->
            if (connected) publish(MUSIC_FRAME, "$r,$g,$b,$bright,${if (beat) 1 else 0}")
            else lights.applyMusicFrame(r, g, b, bright, beat)
        }
        handler.postDelayed(recomputeRunnable, RECOMPUTE_MS)
        handler.postDelayed(configRunnable, 10 * 60 * 1000L)
        connect(cfg)
    }

    private val kioskListener: () -> Unit = { handler.post { publishKiosk() } }

    private val configRunnable = object : Runnable {
        override fun run() {
            publishConfig()
            handler.postDelayed(this, 10 * 60 * 1000L)
        }
    }

    /**
     * Retained snapshot of this bridge's settings (no passwords) and device facts, so Home
     * Assistant and assistants can read what can be changed via `config/set`.
     */
    fun publishConfig() {
        val c = config ?: return
        val o = JSONObject()
            .put("bridge_id", bridgeId)
            .put("name", bridgeName)
            .put("platform", "android")
            .put("version", version)
            .put("settings", JSONObject()
                .put("mqtt_host", c.mqttHost).put("mqtt_port", c.mqttPort).put("mqtt_user", c.mqttUser)
                .put("awox_user", c.awoxUser)
                .put("debug_log", BridgeLog.verbose)
                .put("kiosk_enabled", c.kioskEnabled)
                .put("kiosk_url", c.kioskUrl)
                .put("kiosk_screen_timeout", c.kioskScreenTimeout)
                .put("kiosk_brightness", c.kioskBrightness)
                .put("assistant_access", c.assistantAccess)
                .put("voice_enabled", c.voiceEnabled)
                .put("mcp_port", if (c.mcpEnabled) McpServer.PORT else JSONObject.NULL)
                .put("lamps", c.devices.count { it.isLight })
                .put("assigned_lamps", c.assignedLamps?.let { JSONArray(it.sorted()) } ?: "all"))
            .put("device", try { deviceInfo() } catch (_: Throwable) { JSONObject() })
        publish(configTopic, o.toString(), retain = true, qos = 1)
    }

    fun stop() {
        KioskControl.listeners.remove(kioskListener)
        running = false
        handler.removeCallbacks(recomputeRunnable)
        handler.removeCallbacks(configRunnable)
        lights.stopMic()
        val c = client ?: return
        client = null
        // hand over: our links are gone; lamps nobody else reaches become unavailable
        val orphaned = devices.filter { d ->
            links[d.meshId].orEmpty().any { (bid, sc) -> bid != bridgeId && sc > 0 && bridges[bid]?.online == true }.not()
        }
        Thread {
            try {
                if (c.isConnected) {
                    for (d in devices) c.publish("$BASE/light/${d.meshId}/link/$bridgeId", "0".toByteArray(), 1, true)
                    for (d in orphaned) c.publish(lampAvailTopic(d.meshId), "offline".toByteArray(), 1, true)
                    c.publish(avail, "offline".toByteArray(), 1, true).waitForCompletion(2000)
                    c.disconnect().waitForCompletion(2000)
                }
                c.close()
            } catch (_: Throwable) { }
        }.start()
    }

    private fun connect(cfg: Config) {
        if (!running) return
        val uri = "tcp://${cfg.mqttHost}:${cfg.mqttPort}"
        try {
            val c = MqttAsyncClient(uri, "eglo-bridge-$bridgeId-" + System.currentTimeMillis() % 100000, MemoryPersistence())
            client = c
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String) {
                    handler.post { onConnected(reconnect) }
                }
                override fun connectionLost(cause: Throwable?) {
                    handler.post { BridgeLog.w("MQTT-Verbindung verloren: ${cause?.message ?: "?"}") }
                }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    val payload = String(message.payload)
                    if (topic == MUSIC_FRAME) { onFrame(payload); return }   // hot path, no hop
                    if (payload.isEmpty()) topicCache.remove(topic) else topicCache[topic] = payload
                    handler.post { onMessage(topic, payload) }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 30
                maxInflight = 200
                if (cfg.mqttUser.isNotEmpty()) {
                    userName = cfg.mqttUser
                    password = cfg.mqttPassword.toCharArray()
                }
                setWill(avail, "offline".toByteArray(), 1, true)
            }
            BridgeLog.i("Verbinde mit MQTT $uri als „$bridgeName“ …")
            c.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {}
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    handler.post {
                        BridgeLog.e("MQTT-Verbindung fehlgeschlagen: ${exception?.message ?: "?"} – neuer Versuch in 15 s")
                        try { c.close() } catch (_: Throwable) { }
                        if (client == c) client = null
                        handler.postDelayed({ if (running && client == null) connect(cfg) }, 15000)
                    }
                }
            })
        } catch (t: Throwable) {
            BridgeLog.e("MQTT-Client-Fehler", t)
            handler.postDelayed({ if (running && client == null) connect(cfg) }, 15000)
        }
    }

    private fun publish(topic: String, payload: String, retain: Boolean = false, qos: Int = 0) {
        val c = client ?: return
        if (!c.isConnected) return
        try {
            c.publish(topic, payload.toByteArray(), qos, retain)
        } catch (t: Throwable) {
            android.util.Log.w("EgloBridge", "publish failed: ${t.message}")
        }
    }

    private fun onConnected(reconnect: Boolean) {
        BridgeLog.i(if (reconnect) "MQTT wieder verbunden" else "MQTT verbunden")
        val c = client ?: return
        // what we believed while offline is not on the broker: re-learn from retained messages
        lampAvail.clear()
        try {
            c.subscribe(arrayOf(
                "$BASE/bridge/+/status", "$BASE/bridge/+/info", cmdTopic, debugSet, updateInstall,
                "$screenTopic/set", "$brightTopic/set", "$timeoutTopic/set", "$urlTopic/set",
                "$BASE/light/#", "$BASE/music/#", SENS, SENS_SET, SPEED, SPEED_SET,
                "$FIRE_SPEED/#", "$FIRE_STRENGTH/#", "$FIRE_BRIGHT/#",
                "$lampAssign/+/set", "$allAssign/set", configSet,
                "$BASE/bridge/+/state", "$BASE/bridge/+/config"
            ), IntArray(23))
        } catch (t: Throwable) {
            BridgeLog.e("MQTT-Subscribe fehlgeschlagen", t)
        }
        publish(avail, "online", retain = true, qos = 1)
        publish(info, JSONObject().put("name", bridgeName).put("version", version).put("mic", true)
            .put("platform", "android").toString(), retain = true, qos = 1)
        publish(stateText, bridgeState, retain = true)
        publish(debugTopic, if (BridgeLog.verbose) "ON" else "OFF", retain = true)
        micState = ""
        updateMic()
        publishKiosk()
        publishConfig()
        publishUpdate(inProgress = false)
        cleanupLegacyDiscovery()
        publishDiscovery()
        publishAssignment()
        published.clear()  // force re-publish of link scores
        // give retained messages a moment to arrive, then decide ownership
        handler.postDelayed({ recompute(); ensureSelectionDefaults() }, 2500)
    }

    // ================================================================= discovery

    private fun lampAvailTopic(id: Int) = "$BASE/light/$id/availability"
    private fun stateTopic(id: Int) = "$BASE/light/$id/state"
    private fun commandTopic(id: Int) = "$BASE/light/$id/set"

    private val bridgeDevice: JSONObject
        get() = JSONObject()
            .put("identifiers", JSONArray().put("eglo_bridge_$bridgeId"))
            .put("name", "EGLO Bridge $bridgeName")
            .put("manufacturer", "eglo-ble-bridge")
            .put("model", "Android-Tablet")
            .put("sw_version", version)

    private val globalDevice: JSONObject
        get() = JSONObject()
            .put("identifiers", JSONArray().put("eglo_bridge_global"))
            .put("name", "EGLO Musik & Effekte")
            .put("manufacturer", "eglo-ble-bridge")
            .put("model", "Gemeinsame Steuerung")

    private fun colorModes(d: MeshDevice): JSONArray {
        val a = JSONArray()
        if (d.hasTemperature) a.put("color_temp")
        if (d.hasColor) a.put("rgb")
        if (a.length() == 0) a.put(if (d.hasDimming) "brightness" else "onoff")
        return a
    }

    /** Entities of the single-bridge versions (≤ Build 11) that moved to per-bridge ids. */
    private fun cleanupLegacyDiscovery() {
        for (t in listOf(
            "homeassistant/sensor/eglo_bridge_state/config", "homeassistant/sensor/eglo_bridge_log/config",
            "homeassistant/button/eglo_bridge_reconnect/config", "homeassistant/switch/eglo_bridge_debug/config"
        )) publish(t, "", retain = true, qos = 1)
    }

    private fun publishDiscovery() {
        for (d in devices.filter { it.isLight }) {
            val id = d.meshId
            val cfg = JSONObject()
                .put("name", JSONObject.NULL)
                .put("unique_id", "eglo_bridge_$id")
                .put("schema", "json")
                .put("command_topic", commandTopic(id))
                .put("state_topic", stateTopic(id))
                .put("availability_topic", lampAvailTopic(id))
                .put("supported_color_modes", colorModes(d))
                .put("device", JSONObject()
                    .put("identifiers", JSONArray().put("eglo_bridge_dev_$id"))
                    .put("name", d.name.trim())
                    .put("manufacturer", d.vendor.ifEmpty { "EGLO" })
                    .put("model", d.model.replace('_', ' '))
                    .put("sw_version", d.firmware))
            if (d.hasColor || d.hasTemperature || d.hasDimming) {
                cfg.put("brightness", true).put("brightness_scale", 255)
            }
            if (d.hasTemperature) {
                cfg.put("color_temp_kelvin", true)
                    .put("min_kelvin", LightController.MIN_K).put("max_kelvin", LightController.MAX_K)
            }
            if (d.hasColor) {
                cfg.put("effect", true).put("effect_list", JSONArray(LightController.EFFECTS))
                publish("homeassistant/switch/eglo_bridge_music_$id/config", JSONObject()
                    .put("name", "Bei Musikmodus dabei")
                    .put("unique_id", "eglo_bridge_music_$id")
                    .put("command_topic", "$BASE/light/$id/music/set")
                    .put("state_topic", "$BASE/light/$id/music")
                    .put("icon", "mdi:music-circle")
                    .put("entity_category", "config")
                    .put("device", JSONObject().put("identifiers", JSONArray().put("eglo_bridge_dev_$id")))
                    .toString(), retain = true, qos = 1)
            }
            publish("homeassistant/light/eglo_bridge_$id/config", cfg.toString(), retain = true, qos = 1)
            if (d.hasColor || d.hasTemperature) {
                for (n in 1..2) {
                    publish("homeassistant/button/eglo_bridge_${id}_fav${n}/config", JSONObject()
                        .put("name", "Favorit $n")
                        .put("unique_id", "eglo_bridge_${id}_fav$n")
                        .put("command_topic", "$BASE/light/$id/fav/set")
                        .put("payload_press", "recall$n")
                        .put("icon", if (n == 1) "mdi:numeric-1-circle" else "mdi:numeric-2-circle")
                        .put("device", JSONObject().put("identifiers", JSONArray().put("eglo_bridge_dev_$id")))
                        .toString(), retain = true, qos = 1)
                    publish("homeassistant/button/eglo_bridge_${id}_fav${n}_save/config", JSONObject()
                        .put("name", "Favorit $n speichern (aktuelle Einstellung)")
                        .put("unique_id", "eglo_bridge_${id}_fav${n}_save")
                        .put("command_topic", "$BASE/light/$id/fav/set")
                        .put("payload_press", "save$n")
                        .put("icon", "mdi:content-save")
                        .put("entity_category", "config")
                        .put("device", JSONObject().put("identifiers", JSONArray().put("eglo_bridge_dev_$id")))
                        .toString(), retain = true, qos = 1)
                }
            }
            if (d.hasColor) {
                for (ls in LAMP_SETTINGS) {
                    publish("homeassistant/number/eglo_bridge_${id}_${ls[0]}/config", JSONObject()
                        .put("name", ls[1])
                        .put("unique_id", "eglo_bridge_${id}_${ls[0]}")
                        .put("command_topic", "$BASE/light/$id/${ls[0]}/set")
                        .put("state_topic", "$BASE/light/$id/${ls[0]}")
                        .put("min", ls[3].toInt()).put("max", 100).put("step", 5)
                        .put("unit_of_measurement", "%")
                        .put("mode", "slider")
                        .put("icon", ls[2])
                        .put("device", JSONObject().put("identifiers", JSONArray().put("eglo_bridge_dev_$id")))
                        .toString(), retain = true, qos = 1)
                }
            }
        }

        // ---- shared music / effect entities
        publish("homeassistant/switch/eglo_bridge_music/config", JSONObject()
            .put("name", "Musikmodus")
            .put("unique_id", "eglo_bridge_music")
            .put("command_topic", MUSIC_SET)
            .put("state_topic", MUSIC)
            .put("icon", "mdi:music")
            .put("device", globalDevice).toString(), retain = true, qos = 1)
        publish("homeassistant/number/eglo_bridge_music_sensitivity/config", JSONObject()
            .put("name", "Musik-Empfindlichkeit")
            .put("unique_id", "eglo_bridge_music_sensitivity")
            .put("command_topic", SENS_SET)
            .put("state_topic", SENS)
            .put("min", 50).put("max", 200).put("step", 5)
            .put("unit_of_measurement", "%")
            .put("mode", "slider")
            .put("icon", "mdi:microphone-settings")
            .put("device", globalDevice).toString(), retain = true, qos = 1)
        publish("homeassistant/number/eglo_bridge_effect_speed/config", JSONObject()
            .put("name", "Effekt-Tempo")
            .put("unique_id", "eglo_bridge_effect_speed")
            .put("command_topic", SPEED_SET)
            .put("state_topic", SPEED)
            .put("min", 0).put("max", 100).put("step", 5)
            .put("unit_of_measurement", "%")
            .put("mode", "slider")
            .put("icon", "mdi:speedometer")
            .put("device", globalDevice).toString(), retain = true, qos = 1)
        for ((topic, uid, name, icon, min) in listOf(
            Quint(FIRE_SPEED, "eglo_bridge_fire_speed", "Lagerfeuer-Tempo", "mdi:fire", 0),
            Quint(FIRE_STRENGTH, "eglo_bridge_fire_strength", "Lagerfeuer-Stärke", "mdi:fire-alert", 0),
            Quint(FIRE_BRIGHT, "eglo_bridge_fire_brightness", "Lagerfeuer-Helligkeit", "mdi:brightness-6", 5)
        )) {
            publish("homeassistant/number/$uid/config", JSONObject()
                .put("name", name)
                .put("unique_id", uid)
                .put("command_topic", "$topic/set")
                .put("state_topic", topic)
                .put("min", min).put("max", 100).put("step", 5)
                .put("unit_of_measurement", "%")
                .put("mode", "slider")
                .put("icon", icon)
                .put("device", globalDevice).toString(), retain = true, qos = 1)
        }
        publishSourceSelect(force = true)

        // ---- per-bridge entities
        publish("homeassistant/sensor/eglo_bridge_${bridgeId}_state/config", JSONObject()
            .put("name", "Status")
            .put("unique_id", "eglo_bridge_${bridgeId}_state")
            .put("state_topic", stateText)
            .put("availability_topic", avail)
            .put("icon", "mdi:bluetooth-connect")
            .put("entity_category", "diagnostic")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        publish("homeassistant/sensor/eglo_bridge_${bridgeId}_log/config", JSONObject()
            .put("name", "Log")
            .put("unique_id", "eglo_bridge_${bridgeId}_log")
            .put("state_topic", logTopic)
            .put("availability_topic", avail)
            .put("icon", "mdi:text-box-outline")
            .put("entity_category", "diagnostic")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        publish("homeassistant/switch/eglo_bridge_${bridgeId}_debug/config", JSONObject()
            .put("name", "Debug-Log")
            .put("unique_id", "eglo_bridge_${bridgeId}_debug")
            .put("command_topic", debugSet)
            .put("state_topic", debugTopic)
            .put("availability_topic", avail)
            .put("icon", "mdi:bug")
            .put("entity_category", "config")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        for ((cmd, name, icon) in listOf(
            Triple("restart", "Bridge neu starten", "mdi:restart-alert"),
            Triple("reimport", "Lampen neu importieren", "mdi:cloud-download"),
            Triple("check_update", "Nach Update suchen", "mdi:update")
        )) {
            publish("homeassistant/button/eglo_bridge_${bridgeId}_$cmd/config", JSONObject()
                .put("name", name)
                .put("unique_id", "eglo_bridge_${bridgeId}_$cmd")
                .put("command_topic", cmdTopic)
                .put("payload_press", cmd)
                .put("availability_topic", avail)
                .put("icon", icon)
                .put("entity_category", "config")
                .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        }
        publish("homeassistant/update/eglo_bridge_${bridgeId}_update/config", JSONObject()
            .put("name", "App-Update")
            .put("unique_id", "eglo_bridge_${bridgeId}_update")
            .put("state_topic", updateTopic)
            .put("command_topic", updateInstall)
            .put("payload_install", "install")
            .put("availability_topic", avail)
            .put("entity_category", "config")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        publish("homeassistant/switch/eglo_bridge_${bridgeId}_lamps_all/config", JSONObject()
            .put("name", "Zuständig für alle Lampen")
            .put("unique_id", "eglo_bridge_${bridgeId}_lamps_all")
            .put("command_topic", "$allAssign/set")
            .put("state_topic", allAssign)
            .put("availability_topic", avail)
            .put("icon", "mdi:lightbulb-group")
            .put("entity_category", "config")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        for (d in devices.filter { it.isLight }) {
            publish("homeassistant/switch/eglo_bridge_${bridgeId}_lamp_${d.meshId}/config", JSONObject()
                .put("name", "Zuständig: ${d.name.trim()}")
                .put("unique_id", "eglo_bridge_${bridgeId}_lamp_${d.meshId}")
                .put("command_topic", "$lampAssign/${d.meshId}/set")
                .put("state_topic", "$lampAssign/${d.meshId}")
                .put("availability_topic", avail)
                .put("icon", "mdi:lightbulb-on-outline")
                .put("entity_category", "config")
                .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        }
        publish("homeassistant/sensor/eglo_bridge_${bridgeId}_mic/config", JSONObject()
            .put("name", "Mikrofon")
            .put("unique_id", "eglo_bridge_${bridgeId}_mic")
            .put("state_topic", micTopic)
            .put("availability_topic", avail)
            .put("icon", "mdi:microphone")
            .put("entity_category", "diagnostic")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)

        // ---- kiosk (dashboard screen of this tablet)
        publish("homeassistant/switch/eglo_bridge_${bridgeId}_screen/config", JSONObject()
            .put("name", "Bildschirm")
            .put("unique_id", "eglo_bridge_${bridgeId}_screen")
            .put("command_topic", "$screenTopic/set")
            .put("state_topic", screenTopic)
            .put("availability_topic", avail)
            .put("icon", "mdi:tablet-dashboard")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        publish("homeassistant/number/eglo_bridge_${bridgeId}_brightness/config", JSONObject()
            .put("name", "Bildschirmhelligkeit")
            .put("unique_id", "eglo_bridge_${bridgeId}_brightness")
            .put("command_topic", "$brightTopic/set")
            .put("state_topic", brightTopic)
            .put("availability_topic", avail)
            .put("min", 1).put("max", 100).put("step", 1)
            .put("unit_of_measurement", "%")
            .put("mode", "slider")
            .put("icon", "mdi:brightness-6")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        publish("homeassistant/number/eglo_bridge_${bridgeId}_screen_timeout/config", JSONObject()
            .put("name", "Bildschirm aus nach")
            .put("unique_id", "eglo_bridge_${bridgeId}_screen_timeout")
            .put("command_topic", "$timeoutTopic/set")
            .put("state_topic", timeoutTopic)
            .put("availability_topic", avail)
            .put("min", 0).put("max", 120).put("step", 1)
            .put("unit_of_measurement", "min")
            .put("mode", "box")
            .put("icon", "mdi:timer-outline")
            .put("entity_category", "config")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        publish("homeassistant/text/eglo_bridge_${bridgeId}_url/config", JSONObject()
            .put("name", "Dashboard-Adresse")
            .put("unique_id", "eglo_bridge_${bridgeId}_url")
            .put("command_topic", "$urlTopic/set")
            .put("state_topic", urlTopic)
            .put("availability_topic", avail)
            .put("max", 255)
            .put("icon", "mdi:web")
            .put("entity_category", "config")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        publish("homeassistant/button/eglo_bridge_${bridgeId}_reload/config", JSONObject()
            .put("name", "Dashboard neu laden")
            .put("unique_id", "eglo_bridge_${bridgeId}_reload")
            .put("command_topic", cmdTopic)
            .put("payload_press", "reload")
            .put("availability_topic", avail)
            .put("icon", "mdi:refresh")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        publish("homeassistant/sensor/eglo_bridge_${bridgeId}_battery/config", JSONObject()
            .put("name", "Akku")
            .put("unique_id", "eglo_bridge_${bridgeId}_battery")
            .put("state_topic", batteryTopic)
            .put("availability_topic", avail)
            .put("device_class", "battery")
            .put("unit_of_measurement", "%")
            .put("state_class", "measurement")
            .put("entity_category", "diagnostic")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        publish("homeassistant/binary_sensor/eglo_bridge_${bridgeId}_charging/config", JSONObject()
            .put("name", "Lädt")
            .put("unique_id", "eglo_bridge_${bridgeId}_charging")
            .put("state_topic", chargingTopic)
            .put("availability_topic", avail)
            .put("device_class", "battery_charging")
            .put("entity_category", "diagnostic")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        publish("homeassistant/binary_sensor/eglo_bridge_${bridgeId}_kiosk/config", JSONObject()
            .put("name", "Dashboard sichtbar")
            .put("unique_id", "eglo_bridge_${bridgeId}_kiosk")
            .put("state_topic", kioskTopic)
            .put("availability_topic", avail)
            .put("icon", "mdi:monitor-dashboard")
            .put("entity_category", "diagnostic")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)

        if (config?.allowsMqttSetup == false) {
            // decentral only: no setup entities in Home Assistant
            publish("homeassistant/text/eglo_bridge_${bridgeId}_name/config", "", retain = true, qos = 1)
            publish("homeassistant/number/eglo_bridge_${bridgeId}_volume/config", "", retain = true, qos = 1)
        } else {
        publish("homeassistant/text/eglo_bridge_${bridgeId}_name/config", JSONObject()
            .put("name", "Name")
            .put("unique_id", "eglo_bridge_${bridgeId}_name")
            .put("command_topic", configSet)
            .put("command_template", "{\"name\": {{ value | tojson }}}")
            .put("state_topic", configTopic)
            .put("value_template", "{{ value_json.name }}")
            .put("availability_topic", avail)
            .put("min", 1).put("max", 40)
            .put("icon", "mdi:rename")
            .put("entity_category", "config")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        publish("homeassistant/number/eglo_bridge_${bridgeId}_volume/config", JSONObject()
            .put("name", "Medienlautstärke")
            .put("unique_id", "eglo_bridge_${bridgeId}_volume")
            .put("command_topic", configSet)
            .put("command_template", "{\"media_volume\": {{ value | int }}}")
            .put("state_topic", configTopic)
            .put("value_template", "{{ value_json.device.media_volume }}")
            .put("availability_topic", avail)
            .put("min", 0).put("max", 100).put("step", 5)
            .put("unit_of_measurement", "%")
            .put("mode", "slider")
            .put("icon", "mdi:volume-high")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
        }
        publish("homeassistant/sensor/eglo_bridge_${bridgeId}_ip/config", JSONObject()
            .put("name", "IP-Adresse")
            .put("unique_id", "eglo_bridge_${bridgeId}_ip")
            .put("state_topic", configTopic)
            .put("value_template", "{{ value_json.device.ip }}")
            .put("json_attributes_topic", configTopic)
            .put("json_attributes_template", "{{ value_json.device | tojson }}")
            .put("availability_topic", avail)
            .put("icon", "mdi:ip-network")
            .put("entity_category", "diagnostic")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)

        publish("homeassistant/button/eglo_bridge_${bridgeId}_reconnect/config", JSONObject()
            .put("name", "Neu verbinden")
            .put("unique_id", "eglo_bridge_${bridgeId}_reconnect")
            .put("command_topic", cmdTopic)
            .put("payload_press", "reconnect")
            .put("availability_topic", avail)
            .put("icon", "mdi:restart")
            .put("entity_category", "config")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
    }

    // ================================================================= lamp assignment

    /** Mesh ids of the lamps this bridge is responsible for; null = all. */
    val assignedLamps: Set<Int>? get() = config?.assignedLamps

    private fun publishAssignment() {
        val a = assignedLamps
        publish(allAssign, if (a == null) "ON" else "OFF", retain = true)
        for (d in devices.filter { it.isLight }) {
            publish("$lampAssign/${d.meshId}", if (a == null || d.meshId in a) "ON" else "OFF", retain = true)
        }
    }

    /** Apply a new lamp assignment (null = all lamps), persist it and tell HA. Thread-safe. */
    fun setAssignment(assigned: Set<Int>?) {
        handler.post {
            val lightIds = devices.filter { it.isLight }.map { it.meshId }.toSet()
            val clean = assigned?.filter { it in lightIds }?.toSet()
            config?.assignedLamps = clean
            mesh.setAssigned(clean)
            // mesh.setAssigned posts to this handler too: recompute after it ran
            handler.post {
                publishAssignment()
                recompute()
                onAssignmentChanged?.invoke()
            }
        }
    }

    private fun onAssignMessage(meshId: Int?, on: Boolean) {
        val lightIds = devices.filter { it.isLight }.map { it.meshId }
        if (meshId == null) {  // "all lamps" switch
            if (on) setAssignment(null)
            // switching "all" off keeps the current lamps, but as an explicit list
            // (new lamps from a later import are then not added automatically)
            else if (assignedLamps == null) setAssignment(lightIds.toSet())
            return
        }
        if (meshId !in lightIds) return
        val current = (assignedLamps ?: lightIds.toSet()).toMutableSet()
        if (on) current.add(meshId) else current.remove(meshId)
        setAssignment(if (current.containsAll(lightIds)) null else current)
    }

    /** Select entity "Musikquelle" listing all known tablets. Re-published when the list changes. */
    private fun publishSourceSelect(force: Boolean = false) {
        val names = bridges.values.filter { it.mic }.map { it.name }.distinct().sorted()
        val key = names.joinToString("|")
        if (!force && key == lastSourceOptions) return
        lastSourceOptions = key
        publish("homeassistant/select/eglo_bridge_music_source/config", JSONObject()
            .put("name", "Musikquelle (Tablet-Mikrofon)")
            .put("unique_id", "eglo_bridge_music_source")
            .put("command_topic", SOURCE_SET)
            .put("state_topic", SOURCE_NAME)
            .put("options", JSONArray(names))
            .put("icon", "mdi:tablet")
            .put("device", globalDevice).toString(), retain = true, qos = 1)
        publish(SOURCE_NAME, bridges[effectiveSource()]?.name ?: bridgeName, retain = true)
    }

    // ================================================================= ownership

    private val recomputeRunnable = object : Runnable {
        override fun run() {
            recompute()
            handler.postDelayed(this, RECOMPUTE_MS)
        }
    }

    /** Own score comes from the cache refreshed in [recompute] (thread safe for the UI). */
    private fun score(id: Int, bid: String): Int =
        if (bid == bridgeId) myLinks[id] ?: 0 else links[id]?.get(bid) ?: 0

    /** Bridge that controls the lamp: best link score, ties -> smaller id. null = nobody reaches it. */
    fun ownerOf(id: Int): String? {
        var best: String? = null
        var bestScore = 0
        for ((bid, b) in bridges) {
            if (!b.online && bid != bridgeId) continue
            val s = score(id, bid)
            if (s <= 0) continue
            if (s > bestScore || (s == bestScore && best != null && bid < best)) {
                best = bid; bestScore = s
            }
        }
        return best
    }

    fun isOwner(id: Int) = ownerOf(id) == bridgeId

    private fun ownedLights() = devices.filter { it.isLight && isOwner(it.meshId) }.map { it.meshId }

    private fun recompute() {
        if (!running) return
        for (d in devices.filter { it.isLight }) {
            val id = d.meshId
            val s = mesh.linkScore(id)
            myLinks[id] = s
            if (published[id] != s && connected) {
                published[id] = s
                publish("$BASE/light/$id/link/$bridgeId", s.toString(), retain = true, qos = 1)
            }
            val owner = ownerOf(id)
            when {
                owner == bridgeId -> {
                    if (lampAvail[id] != true) {
                        lampAvail[id] = true
                        publish(lampAvailTopic(id), "online", retain = true, qos = 1)
                        lights.localState(id)?.takeIf { it.known }?.let { publishState(id, it) }
                        if (musicOn && selected(id)) lights.setMusicTarget(id, true)
                    }
                }
                owner == null -> {
                    if (lampAvail[id] != false) {
                        lampAvail[id] = false
                        publish(lampAvailTopic(id), "offline", retain = true, qos = 1)
                    }
                    lights.setMusicTarget(id, false)
                    lights.dropSoftwareEffect(id)
                }
                else -> {  // another tablet controls it
                    lights.setMusicTarget(id, false)
                    lights.dropSoftwareEffect(id)
                }
            }
        }
        publishSourceSelect()
        updateMic()
    }

    // ================================================================= states

    private fun onLocalState(id: Int, st: LightController.LightState) {
        recompute()
        val owner = ownerOf(id)
        if (owner == bridgeId || (owner == null && st.available)) publishState(id, st)
    }

    private fun publishState(id: Int, st: LightController.LightState) {
        val d = devices.firstOrNull { it.meshId == id } ?: return
        val o = JSONObject().put("state", if (st.on) "ON" else "OFF")
        if (st.colorMode && d.hasColor) {
            o.put("color_mode", "rgb")
            o.put("brightness", st.brightness255)
            o.put("color", JSONObject().put("r", st.r).put("g", st.g).put("b", st.b))
        } else if (d.hasTemperature) {
            o.put("color_mode", "color_temp")
            o.put("brightness", st.brightness255)
            o.put("color_temp", st.kelvin)
        } else if (d.hasDimming || d.hasColor) {
            o.put("color_mode", "brightness")
            o.put("brightness", st.brightness255)
        }
        if (d.hasColor) o.put("effect", st.effect)
        publish(stateTopic(id), o.toString(), retain = true)
    }

    fun onBridgeState(text: String) {
        bridgeState = text
        publish(stateText, text, retain = true)
    }

    fun onLog(line: String) {
        publish(logTopic, line.take(250))
    }

    // ================================================================= music

    private fun selected(id: Int) = selection[id] ?: true

    // ================================================================= favourites

    private fun currentAsCommand(id: Int): JSONObject? {
        val st = uiState(id) ?: return null
        if (!st.known) return null
        val d = devices.firstOrNull { it.meshId == id } ?: return null
        val o = JSONObject().put("state", "ON").put("brightness", st.brightness255.coerceAtLeast(1))
        if (st.colorMode && d.hasColor) {
            o.put("color", JSONObject().put("r", st.r).put("g", st.g).put("b", st.b))
        } else if (d.hasTemperature) {
            o.put("color_temp", st.kelvin)
        }
        // keep a running effect (not music: that depends on the music mode)
        if (st.effect != LightController.EFFECT_NONE && st.effect != LightController.EFFECT_MUSIC) {
            o.put("effect", st.effect)
        }
        return o
    }

    private fun saveFavorite(id: Int, n: Int) {
        if (n !in 1..2) return
        val cmd = currentAsCommand(id) ?: run { BridgeLog.w("Favorit $n: Zustand der Lampe unbekannt"); return }
        favorites["$id/$n"] = cmd
        publish("$BASE/light/$id/fav$n", cmd.toString(), retain = true, qos = 1)
        BridgeLog.i("Favorit $n für ${mesh.deviceName(id)} gespeichert: $cmd")
    }

    private fun recallFavorite(id: Int, n: Int) {
        val cmd = favorites["$id/$n"]
        if (cmd == null) {
            BridgeLog.w("Favorit $n für ${mesh.deviceName(id)} ist noch nicht belegt")
            return
        }
        lights.command(id, JSONObject(cmd.toString()))
    }

    /** Favourite as stored (for the app buttons), or null. */
    fun favorite(id: Int, n: Int): JSONObject? = favorites["$id/$n"]

    fun pressFavorite(id: Int, n: Int, save: Boolean) {
        val payload = (if (save) "save" else "recall") + n
        if (connected) publish("$BASE/light/$id/fav/set", payload)
        else handler.post { if (save) saveFavorite(id, n) else recallFavorite(id, n) }
    }

    /** Write a per-lamp setting for every colour lamp (used by the global sliders). */
    private fun setAllLamps(key: String, value: Int) {
        for (d in devices.filter { it.isLight && it.hasColor }) {
            if (connected) publish("$BASE/light/${d.meshId}/$key", value.toString(), retain = true)
            else applyLampSetting(d.meshId, key, value)
        }
    }

    private fun applyLampSetting(id: Int, key: String, value: Int) {
        lights.setLampSetting(id, key, value)
        if (key == "effect_speed" && isOwner(id)) lights.applySequenceSpeed(listOf(id), value)
    }

    private data class Quint(val a: String, val b: String, val c: String, val d: String, val e: Int)

    private var musicKnown = false
    private var sensKnown = false

    private fun ensureSelectionDefaults() {
        if (!musicKnown) publish(MUSIC, "OFF", retain = true)
        if (!sensKnown) publish(SENS, sensitivityPct.toString(), retain = true)
        if (speed == 50 && !speedKnown) publish(SPEED, "50", retain = true)
        // per-lamp setting defaults if nobody has set them yet
        for (d in devices.filter { it.isLight && it.hasColor }) {
            for (key in LAMP_SETTING_KEYS) {
                if (!lampKnown.contains("${d.meshId}/$key")) {
                    publish("$BASE/light/${d.meshId}/$key", lights.lampSetting(d.meshId, key).toString(), retain = true)
                }
            }
        }
        // defaults for the campfire sliders if nobody has set them yet
        if (!fireKnown.contains(FIRE_SPEED)) publish(FIRE_SPEED, lights.fireSpeed.toString(), retain = true)
        if (!fireKnown.contains(FIRE_STRENGTH)) publish(FIRE_STRENGTH, lights.fireStrength.toString(), retain = true)
        if (!fireKnown.contains(FIRE_BRIGHT)) publish(FIRE_BRIGHT, lights.fireBrightness.toString(), retain = true)
        for (d in devices.filter { it.isLight && it.hasColor }) {
            if (!selection.containsKey(d.meshId)) publish("$BASE/light/${d.meshId}/music", "ON", retain = true)
        }
        if (musicSourceId == null) publish(SOURCE_NAME, bridges[effectiveSource()]?.name ?: bridgeName, retain = true)
    }

    /** The tablet whose microphone is used: the chosen one if online, else the first online tablet. */
    fun effectiveSource(): String {
        val chosen = musicSourceId
        // a chosen bridge that cannot listen (e.g. stale retained choice of a Pi without microphone) is skipped
        if (chosen != null && bridges[chosen]?.mic != false &&
            (chosen == bridgeId || bridges[chosen]?.online == true)) return chosen
        return bridges.filter { (it.value.online || it.key == bridgeId) && it.value.mic }.keys.minOrNull() ?: bridgeId
    }

    private fun requestMusic(on: Boolean) {
        if (connected) publish(MUSIC_SET, if (on) "ON" else "OFF")
        else setMusic(on)
    }

    private fun setMusic(on: Boolean) {
        val changed = musicOn != on
        musicOn = on
        if (changed) BridgeLog.i("Musikmodus " + if (on) "an" else "aus")
        lights.setMusicMode(on, if (on) ownedLights().filter { selected(it) && lights.isAvailable(it) } else emptyList())
        updateMic()
    }

    private fun updateMic() {
        val shouldListen = musicOn && effectiveSource() == bridgeId
        if (shouldListen && !lights.micRunning) {
            if (!lights.startMic()) {
                BridgeLog.w("Mikrofon konnte nicht gestartet werden – Berechtigung in der App erteilen")
                setMicState(if (hasMicPermission()) "kein Zugriff (Bridge neu starten, während die App offen ist)"
                    else "keine Berechtigung")
                return
            }
        } else if (!shouldListen && lights.micRunning) {
            lights.stopMic()
        }
        setMicState(when {
            lights.micRunning -> "hört zu"
            !hasMicPermission() -> "keine Berechtigung"
            else -> "bereit"
        })
    }

    private fun setMicState(s: String) {
        if (s == micState) return
        micState = s
        publish(micTopic, s, retain = true)
    }

    private fun publishKiosk() {
        val c = config ?: return
        publish(screenTopic, if (KioskControl.screenOn) "ON" else "OFF", retain = true)
        publish(brightTopic, c.kioskBrightness.toString(), retain = true)
        publish(timeoutTopic, c.kioskScreenTimeout.toString(), retain = true)
        publish(urlTopic, c.kioskUrl, retain = true)
        publish(kioskTopic, if (KioskControl.visible) "ON" else "OFF", retain = true)
    }

    fun publishBattery(percent: Int, charging: Boolean) {
        publish(batteryTopic, percent.toString(), retain = true)
        publish(chargingTopic, if (charging) "ON" else "OFF", retain = true)
    }

    /** Install request from the kiosk update dialog. */
    fun requestInstallUpdate() = onServiceCommand?.invoke("install_update")

    /** State of the HA update entity for this tablet's app. */
    fun publishUpdate(inProgress: Boolean) {
        val latest = Updater.latest
        val o = JSONObject()
            .put("installed_version", version)
            .put("latest_version", latest?.versionName ?: version)
            .put("title", "EGLO Bridge App")
            .put("release_url", "https://github.com/daTobi1/eglo-ble-bridge/releases/tag/latest")
            .put("in_progress", inProgress)
        latest?.notes?.takeIf { it.isNotBlank() }?.let { o.put("release_summary", it.take(255)) }
        publish(updateTopic, o.toString(), retain = true)
    }

    private fun onFrame(payload: String) {
        val p = payload.split(',')
        if (p.size < 5) return
        val r = p[0].toIntOrNull() ?: return
        val g = p[1].toIntOrNull() ?: return
        val b = p[2].toIntOrNull() ?: return
        val br = p[3].toIntOrNull() ?: return
        lights.applyMusicFrame(r, g, b, br, p[4] == "1")
    }

    // ================================================================= incoming

    private fun onMessage(topic: String, payload: String) {
        val v = payload.trim()
        if (topic == "$allAssign/set") { onAssignMessage(null, v.uppercase() == "ON"); return }
        if (topic.startsWith("$lampAssign/") && topic.endsWith("/set")) {
            topic.removePrefix("$lampAssign/").removeSuffix("/set").toIntOrNull()
                ?.let { onAssignMessage(it, v.uppercase() == "ON") }
            return
        }
        when (topic) {
            configSet -> {
                if (config?.allowsMqttSetup == false) {
                    BridgeLog.w("Einrichtung über Home Assistant/MQTT ist ausgeschaltet (nur dezentral) – ignoriert")
                    return
                }
                try { onConfigSet?.invoke(JSONObject(v)) } catch (_: Exception) {
                    BridgeLog.w("Ungültige Einstellungen: ${v.take(100)}")
                }
                return
            }
            cmdTopic -> {
                when (v) {
                    "reconnect" -> mesh.reconnect()
                    "reload" -> mainHandler.post { KioskControl.ui?.reload() }
                    else -> onServiceCommand?.invoke(v)
                }
                return
            }
            "$screenTopic/set" -> {
                val on = v.uppercase() == "ON"
                mainHandler.post {
                    val k = KioskControl.ui
                    if (k != null) k.setScreen(on) else KioskControl.screenOn = on
                }
                return
            }
            "$brightTopic/set" -> {
                val n = v.toFloatOrNull()?.toInt()?.coerceIn(1, 100) ?: return
                config?.kioskBrightness = n
                mainHandler.post { KioskControl.ui?.setBrightness(n) }
                publishKiosk()
                return
            }
            "$timeoutTopic/set" -> {
                val n = v.toFloatOrNull()?.toInt()?.coerceIn(0, 120) ?: return
                config?.kioskScreenTimeout = n
                publishKiosk()
                return
            }
            "$urlTopic/set" -> {
                if (v.isEmpty()) return
                config?.kioskUrl = v
                mainHandler.post { KioskControl.ui?.loadUrl(v) }
                publishKiosk()
                return
            }
            updateInstall -> { onServiceCommand?.invoke("install_update"); return }
            debugSet -> {
                BridgeLog.verbose = v.uppercase() == "ON"
                BridgeLog.i("Debug-Log " + if (BridgeLog.verbose) "an" else "aus")
                publish(debugTopic, if (BridgeLog.verbose) "ON" else "OFF", retain = true)
                return
            }
            MUSIC_SET -> { publish(MUSIC, if (v.uppercase() == "ON") "ON" else "OFF", retain = true); return }
            MUSIC -> { musicKnown = true; setMusic(v.uppercase() == "ON"); return }
            SOURCE_SET -> {
                val bid = bridges.entries.firstOrNull { it.value.name == v }?.key ?: slug(v)
                publish(SOURCE, bid, retain = true)
                publish(SOURCE_NAME, bridges[bid]?.name ?: v, retain = true)
                return
            }
            SOURCE -> {
                musicSourceId = v.ifEmpty { null }
                BridgeLog.i("Musikquelle: " + (bridges[effectiveSource()]?.name ?: effectiveSource()))
                updateMic()
                return
            }
            SOURCE_NAME -> return
            "$FIRE_SPEED/set", "$FIRE_STRENGTH/set", "$FIRE_BRIGHT/set" -> {
                val n = v.toFloatOrNull()?.toInt()?.coerceIn(0, 100) ?: return
                val stateTopic = topic.removeSuffix("/set")
                publish(stateTopic, n.toString(), retain = true)
                // the global slider sets all lamps
                setAllLamps(stateTopic.removePrefix("$BASE/"), n)
                return
            }
            FIRE_SPEED, FIRE_STRENGTH, FIRE_BRIGHT -> {
                val n = v.toFloatOrNull()?.toInt()?.coerceIn(0, 100) ?: return
                fireKnown.add(topic)
                when (topic) {
                    FIRE_SPEED -> lights.fireSpeed = n
                    FIRE_STRENGTH -> lights.fireStrength = n
                    else -> lights.fireBrightness = n.coerceAtLeast(5)
                }
                return
            }
            SENS_SET -> { v.toFloatOrNull()?.let { publish(SENS, it.toInt().toString(), retain = true) }; return }
            SENS -> {
                sensKnown = true
                v.toFloatOrNull()?.let { sensitivityPct = it.toInt(); lights.musicSensitivity = it / 100f }
                return
            }
            SPEED_SET -> {
                val n = v.toFloatOrNull()?.toInt()?.coerceIn(0, 100) ?: return
                publish(SPEED, n.toString(), retain = true)
                setAllLamps("effect_speed", n)
                return
            }
            SPEED -> { speedKnown = true; v.toFloatOrNull()?.let { speed = it.toInt() }; return }
        }

        val parts = topic.split('/')
        // eglo_bridge/bridge/<bid>/status|info
        if (parts.size == 4 && parts[1] == "bridge") {
            val bid = parts[2]
            val b = bridges.getOrPut(bid) { BridgeInfo(bid, false) }
            when (parts[3]) {
                "status" -> {
                    val online = v == "online"
                    if (bid != bridgeId && b.online != online) {
                        BridgeLog.i("Tablet „${b.name}“ ist ${if (online) "online" else "offline"}")
                    }
                    if (bid != bridgeId) b.online = online
                    else if (!online && running && connected) {
                        // a stale "offline" (goodbye of an older session that overlapped a restart)
                        // must not leave this bridge unavailable in HA while it is running
                        BridgeLog.w("Eigener Status stand auf offline – setze wieder online")
                        publish(avail, "online", retain = true, qos = 1)
                    }
                }
                "info" -> try {
                    val o = JSONObject(v)
                    b.name = o.optString("name", bid)
                    if (bid != bridgeId) b.mic = o.optBoolean("mic", true)
                } catch (_: Exception) { }
            }
            recompute()
            return
        }
        if (parts.size < 4 || parts[1] != "light") return
        val id = parts[2].toIntOrNull() ?: return
        when {
            parts.size == 4 && parts[3] == "set" -> {
                val json = try { JSONObject(v) } catch (_: Exception) {
                    BridgeLog.w("Ungültiger Befehl: $v"); return
                }
                val owner = ownerOf(id)
                if (owner == bridgeId || (owner == null && lights.isAvailable(id))) lights.command(id, json)
            }
            parts.size == 4 && parts[3] == "state" -> {
                if (v.isEmpty()) return
                try { remoteStates[id] = LightController.parseState(JSONObject(v)) } catch (_: Exception) { }
            }
            parts.size == 4 && parts[3] == "availability" -> lampAvail[id] = v == "online"
            parts.size == 5 && parts[3] == "link" -> {
                links.getOrPut(id) { ConcurrentHashMap() }[parts[4]] = v.toIntOrNull() ?: 0
                if (parts[4] != bridgeId) recompute()
            }
            parts.size == 5 && parts[3] == "fav" && parts[4] == "set" -> {
                val owner = ownerOf(id)
                if (owner != bridgeId && !(owner == null && lights.isAvailable(id))) return
                when {
                    v.startsWith("recall") -> recallFavorite(id, v.removePrefix("recall").toIntOrNull() ?: return)
                    v.startsWith("save") -> saveFavorite(id, v.removePrefix("save").toIntOrNull() ?: return)
                }
            }
            parts.size == 4 && (parts[3] == "fav1" || parts[3] == "fav2") -> {
                if (v.isEmpty()) favorites.remove("$id/${parts[3].last()}")
                else try { favorites["$id/${parts[3].last()}"] = JSONObject(v) } catch (_: Exception) { }
            }
            parts.size == 5 && parts[3] in LAMP_SETTING_KEYS && parts[4] == "set" -> {
                val n = v.toFloatOrNull()?.toInt()?.coerceIn(0, 100) ?: return
                publish("$BASE/light/$id/${parts[3]}", n.toString(), retain = true)
            }
            parts.size == 4 && parts[3] in LAMP_SETTING_KEYS -> {
                val n = v.toFloatOrNull()?.toInt()?.coerceIn(0, 100) ?: return
                lampKnown.add("$id/${parts[3]}")
                applyLampSetting(id, parts[3], n)
            }
            parts.size == 5 && parts[3] == "music" && parts[4] == "set" ->
                publish("$BASE/light/$id/music", if (v.uppercase() == "ON") "ON" else "OFF", retain = true)
            parts.size == 4 && parts[3] == "music" -> {
                val on = v.uppercase() == "ON"
                selection[id] = on
                if (musicOn && isOwner(id)) lights.setMusicTarget(id, on && lights.isAvailable(id))
            }
        }
    }

    // ================================================================= API for the app UI

    /** Send a light command; it is executed by whichever tablet controls the lamp. */
    fun sendCommand(id: Int, json: JSONObject) {
        if (connected) publish(commandTopic(id), json.toString())
        else lights.command(id, json)
    }

    /** State to show in the app: our own if we control the lamp, else the shared state. */
    fun uiState(id: Int): LightController.LightState? {
        val local = lights.localState(id)
        val owner = ownerOf(id)
        val st = if (owner == bridgeId || owner == null) local else remoteStates[id]?.copy() ?: local
        st?.available = lampAvail[id] ?: (local?.available == true)
        return st
    }

    fun ownerName(id: Int): String? = ownerOf(id)?.let { bridges[it]?.name ?: it }
    fun musicSelected(id: Int) = selected(id)
    val musicActive get() = musicOn
    fun setMusicSelected(id: Int, on: Boolean) {
        if (connected) publish("$BASE/light/$id/music", if (on) "ON" else "OFF", retain = true)
        else { selection[id] = on; if (musicOn) lights.setMusicTarget(id, on) }
    }
    fun setMusicGlobal(on: Boolean) = handler.post { requestMusic(on) }
    fun setSensitivity(pct: Int) {
        if (connected) publish(SENS, pct.toString(), retain = true) else lights.musicSensitivity = pct / 100f
    }
    fun setSpeed(v: Int) {
        if (connected) publish(SPEED_SET, v.toString()) else setAllLamps("effect_speed", v)
    }
    fun setFire(speed: Int? = null, strength: Int? = null, brightness: Int? = null) {
        if (connected) {
            speed?.let { publish("$FIRE_SPEED/set", it.toString()) }
            strength?.let { publish("$FIRE_STRENGTH/set", it.toString()) }
            brightness?.let { publish("$FIRE_BRIGHT/set", it.toString()) }
        } else {
            speed?.let { lights.fireSpeed = it; setAllLamps("fire_speed", it) }
            strength?.let { lights.fireStrength = it; setAllLamps("fire_strength", it) }
            brightness?.let { lights.fireBrightness = it.coerceAtLeast(5); setAllLamps("fire_brightness", it.coerceAtLeast(5)) }
        }
    }

    fun musicSourceName(): String = bridges[effectiveSource()]?.name ?: effectiveSource()
    fun bridgeNames(): List<String> = bridges.values.filter { it.mic }.map { it.name }.distinct().sorted()
    fun setMusicSource(name: String) {
        if (connected) publish(SOURCE_SET, name)
    }
}
