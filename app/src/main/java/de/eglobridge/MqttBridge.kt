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
import kotlin.math.roundToInt

/**
 * MQTT side of the bridge: Home Assistant discovery, state publishing and
 * command handling (JSON schema lights).
 */
class MqttBridge(
    private val handler: Handler,
    private val mesh: MeshManager,
    private val version: String
) {
    companion object {
        const val BASE = "eglo_bridge"
        const val AVAIL = "$BASE/status"
        const val BRIDGE_STATE = "$BASE/bridge_state"
        const val LOG = "$BASE/log"
        const val CMD = "$BASE/cmd"
        const val MIN_K = 2700
        const val MAX_K = 6500

        /** Same rounding/clamping as the HA AwoX integration. */
        fun convert(value: Int, minFrom: Int, maxFrom: Int, minTo: Int, maxTo: Int): Int {
            val normalized = (value - minFrom).toDouble() / (maxFrom - minFrom)
            val v = (normalized * (maxTo - minTo) + minTo).roundToInt()
            return v.coerceIn(minTo, maxTo)
        }

        fun deviceTempToKelvin(t: Int) = convert(0x7f - t.coerceIn(0, 0x7f), 0, 0x7f, MIN_K, MAX_K)
        fun kelvinToDeviceTemp(k: Int) = 0x7f - convert(k, MIN_K, MAX_K, 0, 0x7f)
    }

    /** Last known (or optimistic) state per mesh id. */
    private class LightState {
        var on = false
        var colorMode = false
        var whiteBrightness = 0x7f
        var temperature = 0x40
        var colorBrightness = 0x64
        var r = 255; var g = 255; var b = 255
        var known = false
    }

    private var client: MqttAsyncClient? = null
    private var devices: List<MeshDevice> = emptyList()
    private val states = HashMap<Int, LightState>()
    private val availability = HashMap<Int, Boolean>()
    private var bridgeState = "startet"
    private var running = false

    fun start(cfg: Config) {
        devices = cfg.devices
        running = true
        connect(cfg)
    }

    fun stop() {
        running = false
        val c = client ?: return
        client = null
        Thread {
            try {
                if (c.isConnected) {
                    c.publish(AVAIL, "offline".toByteArray(), 1, true).waitForCompletion(2000)
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
            val c = MqttAsyncClient(uri, "eglo-ble-bridge-" + System.currentTimeMillis() % 100000, MemoryPersistence())
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
                    handler.post { onMessage(topic, payload) }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 30
                maxInflight = 100
                if (cfg.mqttUser.isNotEmpty()) {
                    userName = cfg.mqttUser
                    password = cfg.mqttPassword.toCharArray()
                }
                setWill(AVAIL, "offline".toByteArray(), 1, true)
            }
            BridgeLog.i("Verbinde mit MQTT $uri …")
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
        try {
            c.subscribe("$BASE/light/+/set", 0)
            c.subscribe(CMD, 0)
        } catch (t: Throwable) {
            BridgeLog.e("MQTT-Subscribe fehlgeschlagen", t)
        }
        publish(AVAIL, "online", retain = true, qos = 1)
        publishDiscovery()
        publish(BRIDGE_STATE, bridgeState, retain = true)
        for (d in devices) {
            publish(deviceAvailTopic(d.meshId), if (availability[d.meshId] == true) "online" else "offline", retain = true)
            states[d.meshId]?.takeIf { it.known }?.let { publishState(d.meshId, it) }
        }
    }

    private fun deviceAvailTopic(id: Int) = "$BASE/light/$id/availability"
    private fun stateTopic(id: Int) = "$BASE/light/$id/state"
    private fun commandTopic(id: Int) = "$BASE/light/$id/set"

    private val bridgeDevice: JSONObject
        get() = JSONObject()
            .put("identifiers", JSONArray().put("eglo_bridge"))
            .put("name", "EGLO BLE Bridge")
            .put("manufacturer", "eglo-ble-bridge")
            .put("model", "Android-Tablet")
            .put("sw_version", version)

    private fun colorModes(d: MeshDevice): JSONArray {
        val a = JSONArray()
        if (d.hasTemperature) a.put("color_temp")
        if (d.hasColor) a.put("rgb")
        if (a.length() == 0) a.put(if (d.hasDimming) "brightness" else "onoff")
        return a
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
                .put("availability", JSONArray()
                    .put(JSONObject().put("topic", AVAIL))
                    .put(JSONObject().put("topic", deviceAvailTopic(id))))
                .put("availability_mode", "all")
                .put("supported_color_modes", colorModes(d))
                .put("device", JSONObject()
                    .put("identifiers", JSONArray().put("eglo_bridge_dev_$id"))
                    .put("name", d.name)
                    .put("manufacturer", d.vendor.ifEmpty { "EGLO" })
                    .put("model", d.model.replace('_', ' '))
                    .put("sw_version", d.firmware)
                    .put("via_device", "eglo_bridge"))
            if (d.hasColor || d.hasTemperature || d.hasDimming) {
                cfg.put("brightness", true).put("brightness_scale", 255)
            }
            if (d.hasTemperature) {
                cfg.put("color_temp_kelvin", true).put("min_kelvin", MIN_K).put("max_kelvin", MAX_K)
            }
            publish("homeassistant/light/eglo_bridge_$id/config", cfg.toString(), retain = true, qos = 1)
        }

        publish("homeassistant/sensor/eglo_bridge_state/config", JSONObject()
            .put("name", "Status")
            .put("unique_id", "eglo_bridge_state")
            .put("state_topic", BRIDGE_STATE)
            .put("availability_topic", AVAIL)
            .put("icon", "mdi:bluetooth-connect")
            .put("entity_category", "diagnostic")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)

        publish("homeassistant/sensor/eglo_bridge_log/config", JSONObject()
            .put("name", "Log")
            .put("unique_id", "eglo_bridge_log")
            .put("state_topic", LOG)
            .put("availability_topic", AVAIL)
            .put("icon", "mdi:text-box-outline")
            .put("entity_category", "diagnostic")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)

        publish("homeassistant/button/eglo_bridge_reconnect/config", JSONObject()
            .put("name", "Neu verbinden")
            .put("unique_id", "eglo_bridge_reconnect")
            .put("command_topic", CMD)
            .put("payload_press", "reconnect")
            .put("availability_topic", AVAIL)
            .put("icon", "mdi:restart")
            .put("entity_category", "config")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)
    }

    // ------------------------------------------------------------- mesh -> HA

    fun onBridgeState(text: String) {
        bridgeState = text
        publish(BRIDGE_STATE, text, retain = true)
    }

    fun onLog(line: String) {
        publish(LOG, line.take(250))
    }

    fun onStatus(s: LightStatus) {
        val st = states.getOrPut(s.meshId) { LightState() }
        st.on = s.on
        st.colorMode = s.colorMode
        st.whiteBrightness = s.whiteBrightness
        st.temperature = s.temperature
        st.colorBrightness = s.colorBrightness
        st.r = s.r; st.g = s.g; st.b = s.b
        st.known = true
        if (availability[s.meshId] != true) {
            availability[s.meshId] = true
            publish(deviceAvailTopic(s.meshId), "online", retain = true)
        }
        publishState(s.meshId, st)
    }

    fun onDeviceOffline(meshId: Int) {
        if (availability[meshId] != false) {
            availability[meshId] = false
            publish(deviceAvailTopic(meshId), "offline", retain = true)
        }
    }

    fun markAllOffline() {
        for (d in devices) onDeviceOffline(d.meshId)
    }

    private fun publishState(id: Int, st: LightState) {
        val d = devices.firstOrNull { it.meshId == id } ?: return
        val o = JSONObject().put("state", if (st.on) "ON" else "OFF")
        if (st.colorMode && d.hasColor) {
            o.put("color_mode", "rgb")
            o.put("brightness", convert(st.colorBrightness, 0x0a, 0x64, 0, 255))
            o.put("color", JSONObject().put("r", st.r).put("g", st.g).put("b", st.b))
        } else if (d.hasTemperature) {
            o.put("color_mode", "color_temp")
            o.put("brightness", convert(st.whiteBrightness, 1, 0x7f, 0, 255))
            o.put("color_temp", deviceTempToKelvin(st.temperature))
        } else if (d.hasDimming || d.hasColor) {
            o.put("color_mode", "brightness")
            o.put("brightness", convert(st.whiteBrightness, 1, 0x7f, 0, 255))
        }
        publish(stateTopic(id), o.toString(), retain = true)
    }

    // ------------------------------------------------------------- HA -> mesh

    private fun onMessage(topic: String, payload: String) {
        if (topic == CMD) {
            if (payload.trim() == "reconnect") mesh.reconnect()
            return
        }
        val parts = topic.split('/')
        if (parts.size != 4 || parts[3] != "set") return
        val id = parts[2].toIntOrNull() ?: return
        val json = try { JSONObject(payload) } catch (_: Exception) {
            BridgeLog.w("Ungültiger Befehl: $payload"); return
        }
        handleCommand(id, json)
    }

    private fun handleCommand(id: Int, root: JSONObject) {
        BridgeLog.i("Befehl für $id: $root")
        val st = states.getOrPut(id) { LightState() }
        var stateSet = false

        root.optJSONObject("color")?.let { c ->
            val r = c.optInt("r", 255); val g = c.optInt("g", 255); val b = c.optInt("b", 255)
            mesh.setColor(id, r, g, b)
            st.r = r; st.g = g; st.b = b
            st.colorMode = true; st.on = true; stateSet = true
        }

        if (root.has("brightness") && !root.has("color_temp") && (root.has("color") || st.colorMode)) {
            val v = convert(root.getInt("brightness"), 0, 255, 0x0a, 0x64)
            mesh.setColorBrightness(id, v)
            st.colorBrightness = v; st.on = true; stateSet = true
        } else if (root.has("brightness")) {
            val v = convert(root.getInt("brightness"), 0, 255, 1, 0x7f)
            mesh.setWhiteBrightness(id, v)
            st.whiteBrightness = v; st.on = true; stateSet = true
        }

        if (root.has("color_temp")) {
            val v = kelvinToDeviceTemp(root.getInt("color_temp"))
            mesh.setWhiteTemperature(id, v)
            st.temperature = v; st.colorMode = false; st.on = true; stateSet = true
        }

        when (root.optString("state").uppercase()) {
            "ON" -> {
                if (!stateSet) mesh.setPower(id, true)
                st.on = true
            }
            "OFF" -> {
                mesh.setPower(id, false)
                st.on = false
            }
        }
        st.known = true
        publishState(id, st)
    }
}
