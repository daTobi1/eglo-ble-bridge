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
    private val version: String,
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

        fun slug(name: String): String {
            val s = name.lowercase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace(Regex("[^a-z0-9]+"), "_").trim('_')
            return s.ifEmpty { "tablet" }
        }
    }

    private class BridgeInfo(var name: String, var online: Boolean)

    // per-bridge topics
    private val bTopic = "$BASE/bridge/$bridgeId"
    private val avail = "$bTopic/status"
    private val info = "$bTopic/info"
    private val stateText = "$bTopic/state"
    private val logTopic = "$bTopic/log"
    private val cmdTopic = "$bTopic/cmd"
    private val debugTopic = "$bTopic/debug"
    private val debugSet = "$bTopic/debug/set"

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
    private val fireKnown = HashSet<String>()

    val connected: Boolean get() = client?.isConnected == true

    // ================================================================= lifecycle

    fun start(cfg: Config) {
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
        connect(cfg)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(recomputeRunnable)
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
                "$BASE/bridge/+/status", "$BASE/bridge/+/info", cmdTopic, debugSet,
                "$BASE/light/#", "$BASE/music/#", SENS, SENS_SET, SPEED, SPEED_SET,
                "$FIRE_SPEED/#", "$FIRE_STRENGTH/#", "$FIRE_BRIGHT/#"
            ), IntArray(13))
        } catch (t: Throwable) {
            BridgeLog.e("MQTT-Subscribe fehlgeschlagen", t)
        }
        publish(avail, "online", retain = true, qos = 1)
        publish(info, JSONObject().put("name", bridgeName).put("version", version).toString(), retain = true, qos = 1)
        publish(stateText, bridgeState, retain = true)
        publish(debugTopic, if (BridgeLog.verbose) "ON" else "OFF", retain = true)
        cleanupLegacyDiscovery()
        publishDiscovery()
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

    /** Select entity "Musikquelle" listing all known tablets. Re-published when the list changes. */
    private fun publishSourceSelect(force: Boolean = false) {
        val names = bridges.values.map { it.name }.distinct().sorted()
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

    private data class Quint(val a: String, val b: String, val c: String, val d: String, val e: Int)

    private fun ensureSelectionDefaults() {
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
        if (chosen != null && (chosen == bridgeId || bridges[chosen]?.online == true)) return chosen
        return bridges.filter { it.value.online || it.key == bridgeId }.keys.minOrNull() ?: bridgeId
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
            if (!lights.startMic()) BridgeLog.w("Mikrofon konnte nicht gestartet werden – Berechtigung in der App erteilen")
        } else if (!shouldListen && lights.micRunning) {
            lights.stopMic()
        }
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
        when (topic) {
            cmdTopic -> { if (v == "reconnect") mesh.reconnect(); return }
            debugSet -> {
                BridgeLog.verbose = v.uppercase() == "ON"
                BridgeLog.i("Debug-Log " + if (BridgeLog.verbose) "an" else "aus")
                publish(debugTopic, if (BridgeLog.verbose) "ON" else "OFF", retain = true)
                return
            }
            MUSIC_SET -> { publish(MUSIC, if (v.uppercase() == "ON") "ON" else "OFF", retain = true); return }
            MUSIC -> { setMusic(v.uppercase() == "ON"); return }
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
                v.toFloatOrNull()?.let { publish(topic.removeSuffix("/set"), it.toInt().coerceIn(0, 100).toString(), retain = true) }
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
                v.toFloatOrNull()?.let { sensitivityPct = it.toInt(); lights.musicSensitivity = it / 100f }
                return
            }
            SPEED_SET -> { v.toFloatOrNull()?.let { publish(SPEED, it.toInt().toString(), retain = true) }; return }
            SPEED -> {
                v.toFloatOrNull()?.let {
                    speed = it.toInt()
                    lights.applySequenceSpeed(ownedLights(), speed)
                }
                return
            }
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
                }
                "info" -> try { b.name = JSONObject(v).optString("name", bid) } catch (_: Exception) { }
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
        if (connected) publish(SPEED, v.toString(), retain = true) else lights.applySequenceSpeed(ownedLights(), v)
    }
    fun setFire(speed: Int? = null, strength: Int? = null, brightness: Int? = null) {
        if (connected) {
            speed?.let { publish(FIRE_SPEED, it.toString(), retain = true) }
            strength?.let { publish(FIRE_STRENGTH, it.toString(), retain = true) }
            brightness?.let { publish(FIRE_BRIGHT, it.toString(), retain = true) }
        } else {
            speed?.let { lights.fireSpeed = it }
            strength?.let { lights.fireStrength = it }
            brightness?.let { lights.fireBrightness = it.coerceAtLeast(5) }
        }
    }

    fun musicSourceName(): String = bridges[effectiveSource()]?.name ?: effectiveSource()
    fun bridgeNames(): List<String> = bridges.values.map { it.name }.distinct().sorted()
    fun setMusicSource(name: String) {
        if (connected) publish(SOURCE_SET, name)
    }
}
