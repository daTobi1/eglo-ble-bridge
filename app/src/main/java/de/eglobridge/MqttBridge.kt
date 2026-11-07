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

/**
 * MQTT side of the bridge: Home Assistant discovery, state publishing and
 * command handling (JSON schema lights).
 */
class MqttBridge(
    private val handler: Handler,
    private val mesh: MeshManager,
    private val lights: LightController,
    private val version: String
) {
    companion object {
        const val BASE = "eglo_bridge"
        const val AVAIL = "$BASE/status"
        const val BRIDGE_STATE = "$BASE/bridge_state"
        const val LOG = "$BASE/log"
        const val CMD = "$BASE/cmd"
        const val DEBUG = "$BASE/debug"
        const val DEBUG_SET = "$BASE/debug/set"
        const val MUSIC = "$BASE/music"
        const val MUSIC_SET = "$BASE/music/set"
        const val SENS = "$BASE/music_sensitivity"
        const val SENS_SET = "$BASE/music_sensitivity/set"
        const val SPEED = "$BASE/effect_speed"
        const val SPEED_SET = "$BASE/effect_speed/set"
    }

    private var client: MqttAsyncClient? = null
    private var devices: List<MeshDevice> = emptyList()
    private var bridgeState = "startet"
    private var running = false

    fun start(cfg: Config) {
        devices = cfg.devices
        running = true
        lights.stateListeners.add { id, st -> publishState(id, st) }
        lights.settingsListeners.add { handler.post { publishSettings() } }
        lights.availabilityListeners.add { id, av ->
            publish(deviceAvailTopic(id), if (av) "online" else "offline", retain = true)
        }
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
            c.subscribe(DEBUG_SET, 0)
            c.subscribe(MUSIC_SET, 0)
            c.subscribe("$BASE/light/+/music/set", 0)
            c.subscribe(SENS_SET, 0)
            c.subscribe(SPEED_SET, 0)
        } catch (t: Throwable) {
            BridgeLog.e("MQTT-Subscribe fehlgeschlagen", t)
        }
        publish(AVAIL, "online", retain = true, qos = 1)
        publishDiscovery()
        publish(BRIDGE_STATE, bridgeState, retain = true)
        publish(DEBUG, if (BridgeLog.verbose) "ON" else "OFF", retain = true)
        publishSettings()
        val snap = lights.snapshot()
        for (d in devices) {
            val st = snap[d.meshId]
            publish(deviceAvailTopic(d.meshId), if (st?.available == true) "online" else "offline", retain = true)
            st?.takeIf { it.known }?.let { publishState(d.meshId, it) }
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
                cfg.put("color_temp_kelvin", true)
                    .put("min_kelvin", LightController.MIN_K).put("max_kelvin", LightController.MAX_K)
            }
            if (d.hasColor) {
                cfg.put("effect", true).put("effect_list", JSONArray(LightController.EFFECTS))
                // per lamp: take part in the music mode
                publish("homeassistant/switch/eglo_bridge_music_$id/config", JSONObject()
                    .put("name", "Bei Musikmodus dabei")
                    .put("unique_id", "eglo_bridge_music_$id")
                    .put("command_topic", "$BASE/light/$id/music/set")
                    .put("state_topic", "$BASE/light/$id/music")
                    .put("availability_topic", AVAIL)
                    .put("icon", "mdi:music-circle")
                    .put("entity_category", "config")
                    .put("device", JSONObject().put("identifiers", JSONArray().put("eglo_bridge_dev_$id")))
                    .toString(), retain = true, qos = 1)
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

        publish("homeassistant/switch/eglo_bridge_debug/config", JSONObject()
            .put("name", "Debug-Log")
            .put("unique_id", "eglo_bridge_debug")
            .put("command_topic", DEBUG_SET)
            .put("state_topic", DEBUG)
            .put("availability_topic", AVAIL)
            .put("icon", "mdi:bug")
            .put("entity_category", "config")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)

        publish("homeassistant/switch/eglo_bridge_music/config", JSONObject()
            .put("name", "Musikmodus (alle Lampen)")
            .put("unique_id", "eglo_bridge_music")
            .put("command_topic", MUSIC_SET)
            .put("state_topic", MUSIC)
            .put("availability_topic", AVAIL)
            .put("icon", "mdi:music")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)

        publish("homeassistant/number/eglo_bridge_music_sensitivity/config", JSONObject()
            .put("name", "Musik-Empfindlichkeit")
            .put("unique_id", "eglo_bridge_music_sensitivity")
            .put("command_topic", SENS_SET)
            .put("state_topic", SENS)
            .put("availability_topic", AVAIL)
            .put("min", 50).put("max", 200).put("step", 5)
            .put("unit_of_measurement", "%")
            .put("mode", "slider")
            .put("icon", "mdi:microphone-settings")
            .put("device", bridgeDevice).toString(), retain = true, qos = 1)

        publish("homeassistant/number/eglo_bridge_effect_speed/config", JSONObject()
            .put("name", "Effekt-Tempo")
            .put("unique_id", "eglo_bridge_effect_speed")
            .put("command_topic", SPEED_SET)
            .put("state_topic", SPEED)
            .put("availability_topic", AVAIL)
            .put("min", 0).put("max", 100).put("step", 5)
            .put("unit_of_measurement", "%")
            .put("mode", "slider")
            .put("icon", "mdi:speedometer")
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

    private fun publishSettings() {
        publish(MUSIC, if (lights.musicActive) "ON" else "OFF", retain = true)
        publish(SENS, (lights.musicSensitivity * 100).toInt().toString(), retain = true)
        publish(SPEED, lights.sequenceSpeed.toString(), retain = true)
        for (d in devices.filter { it.isLight && it.hasColor }) {
            publish("$BASE/light/${d.meshId}/music", if (lights.musicSelected(d.meshId)) "ON" else "OFF", retain = true)
        }
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

    // ------------------------------------------------------------- HA -> mesh

    private fun onMessage(topic: String, payload: String) {
        if (topic == CMD) {
            if (payload.trim() == "reconnect") mesh.reconnect()
            return
        }
        if (topic == DEBUG_SET) {
            BridgeLog.verbose = payload.trim().uppercase() == "ON"
            BridgeLog.i("Debug-Log " + if (BridgeLog.verbose) "an" else "aus")
            publish(DEBUG, if (BridgeLog.verbose) "ON" else "OFF", retain = true)
            return
        }
        when (topic) {
            MUSIC_SET -> {
                if (payload.trim().uppercase() == "ON") {
                    if (!lights.startMusicAll()) publishSettings()
                } else lights.stopAllMusic()
                return
            }
            SENS_SET -> {
                payload.trim().toFloatOrNull()?.let { lights.musicSensitivity = it / 100f }
                return
            }
            SPEED_SET -> {
                payload.trim().toFloatOrNull()?.let { lights.setSequenceSpeed(it.toInt()) }
                return
            }
        }
        val parts = topic.split('/')
        if (parts.size == 5 && parts[3] == "music" && parts[4] == "set") {
            val id = parts[2].toIntOrNull() ?: return
            lights.setMusicSelected(id, payload.trim().uppercase() == "ON")
            return
        }
        if (parts.size != 4 || parts[3] != "set") return
        val id = parts[2].toIntOrNull() ?: return
        val json = try { JSONObject(payload) } catch (_: Exception) {
            BridgeLog.w("Ungültiger Befehl: $payload"); return
        }
        lights.command(id, json)
    }
}
