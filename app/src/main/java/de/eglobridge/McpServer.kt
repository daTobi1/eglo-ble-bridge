package de.eglobridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * MCP endpoint of the bridge (Model Context Protocol, JSON-RPC over HTTP POST, no sessions).
 *
 * An assistant (e.g. Claude) controls lamps and sets up bridges through it. Everything goes
 * through this bridge's own MQTT connection – the assistant needs no MQTT login of its own.
 * Endpoint: `http://<tablet>:8765/mcp`.
 */
class McpServer(private val mqttProvider: () -> MqttBridge?, private val port: Int = PORT) {

    companion object {
        const val PORT = 8765
        private const val B = MqttBridge.BASE
        private val EFFECTS = listOf("Kein Effekt", "Farbwechsel", "Sequenz 2", "Sequenz 3", "Sequenz 4",
            "Sequenz 5", "Sequenz 6", "Sequenz 7", "Kerze", "Lagerfeuer", "Musik")
        private val COMMANDS = listOf("reconnect", "restart", "reimport", "check_update", "install_update", "reload_kiosk")
    }

    private var socket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "mcp-http", isDaemon = true) {
            try {
                val s = ServerSocket(port).also { socket = it }
                BridgeLog.i("MCP-Zugang bereit auf Port $port")
                while (running) {
                    val c = try { s.accept() } catch (_: SocketException) { break }
                    thread(name = "mcp-conn", isDaemon = true) { serve(c) }
                }
            } catch (t: Throwable) {
                BridgeLog.e("MCP-Zugang konnte nicht starten", t)
            }
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Throwable) { }
        socket = null
    }

    // ================================================================= HTTP

    private fun serve(c: Socket) {
        c.use { sock ->
            sock.soTimeout = 15000
            val inp = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val requestLine = readLine(inp) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(inp) ?: return
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val path = (parts.getOrNull(1) ?: "/").substringBefore('?').trimEnd('/')
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = inp.read(body, read, len - read)
                if (n < 0) break
                read += n
            }
            when {
                method == "GET" && (path == "" || path == "/mcp") -> {
                    val m = mqttProvider()
                    respond(out, 200, "text/plain; charset=utf-8",
                        "EGLO Bridge MCP – ${m?.bridgeName ?: "-"}, MQTT ${if (m?.connected == true) "verbunden" else "nicht verbunden"}\n" +
                            "MCP-Endpunkt: POST http://<diese-IP>:$port/mcp\n")
                }
                method == "POST" && path == "/mcp" -> {
                    val text = String(body, Charsets.UTF_8).trim()
                    val reply = try {
                        if (text.startsWith("[")) {
                            val arr = JSONArray(text)
                            val res = JSONArray()
                            for (i in 0 until arr.length()) handle(arr.getJSONObject(i))?.let { res.put(it) }
                            if (res.length() == 0) null else res.toString()
                        } else handle(JSONObject(text))?.toString()
                    } catch (t: Throwable) {
                        rpcError(JSONObject.NULL, -32700, "Ungültiges JSON").toString()
                    }
                    if (reply == null) respond(out, 202, "application/json", "")
                    else respond(out, 200, "application/json", reply)
                }
                method == "DELETE" && path == "/mcp" -> respond(out, 200, "application/json", "")
                else -> respond(out, 404, "text/plain", "not found")
            }
        }
    }

    private fun readLine(inp: BufferedInputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
            if (b.size() > 8192) return null
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 202 -> "Accepted"; 404 -> "Not Found"; else -> "Error" }
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    // ================================================================= JSON-RPC

    private fun rpcError(id: Any, code: Int, msg: String) = JSONObject().put("jsonrpc", "2.0").put("id", id)
        .put("error", JSONObject().put("code", code).put("message", msg))

    private fun handle(req: JSONObject): JSONObject? {
        val id = req.opt("id") ?: return null          // notification
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()
        val result: JSONObject = when (method) {
            "initialize" -> JSONObject()
                .put("protocolVersion", params.optString("protocolVersion").ifEmpty { "2025-06-18" })
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "eglo-bridge").put("version", mqttProvider()?.version ?: "?"))
                .put("instructions", "Steuert EGLO-Connect-Lampen und richtet die EGLO-BLE-Bridges ein (Tablets, Raspberry Pi). " +
                    "Lampen und Bridges per Name, Teilname oder ID. Beginne mit eglo_overview.")
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", toolList())
            "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                try {
                    val out = callTool(name, args)
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text")
                        .put("text", if (out is JSONObject) out.toString(2) else if (out is JSONArray) out.toString(2) else out.toString())))
                } catch (t: Throwable) {
                    JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject()
                        .put("type", "text").put("text", "Fehler: ${t.message}")))
                }
            }
            else -> return rpcError(id, -32601, "Unbekannte Methode $method")
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }

    // ================================================================= tools

    private fun prop(type: String, desc: String, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("type", type).put("description", desc).apply(extra)

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", desc).put("inputSchema", JSONObject()
            .put("type", "object").put("properties", props).put("required", JSONArray(required)))

    private fun toolList() = JSONArray()
        .put(tool("eglo_overview", "Alle Bridges (Tablets/Pi) und Lampen: online, an/aus, Helligkeit, Farbe, welche Bridge steuert.", JSONObject()))
        .put(tool("eglo_set_light",
            "Schaltet Lampen: an/aus, Helligkeit %, Farbtemperatur 2700–6500 K, RGB (#RRGGBB) oder Effekt (${EFFECTS.joinToString()}). lamps=[\"alle\"] für alle.",
            JSONObject()
                .put("lamps", prop("array", "Namen/Teilnamen/IDs oder [\"alle\"]") { put("items", JSONObject().put("type", "string")) })
                .put("on", prop("boolean", "an/aus"))
                .put("brightness_pct", prop("number", "1–100"))
                .put("color_temp_kelvin", prop("integer", "2700–6500"))
                .put("rgb", prop("string", "#RRGGBB"))
                .put("effect", prop("string", "Effekt") { put("enum", JSONArray(EFFECTS)) }),
            listOf("lamps")))
        .put(tool("eglo_bridge_settings", "Einstellungen und Geräteinfo einer Bridge (ohne Passwörter).",
            JSONObject().put("bridge", prop("string", "Name oder ID; leer = diese Bridge"))))
        .put(tool("eglo_configure_bridge",
            "Richtet eine Bridge ein. Schlüssel in settings: name, debug_log, kiosk_enabled, kiosk_url, kiosk_pin, kiosk_screen_timeout, " +
                "kiosk_brightness, media_volume, screen_on (Tablet); screen_timeout, screen_brightness (Pi); mqtt_host, mqtt_port, mqtt_user, " +
                "mqtt_password (werden nur nach erfolgreicher Testverbindung übernommen); awox_user, awox_password; reimport, restart. " +
                "Umbenennen behält Gerät und Entitäten in Home Assistant.",
            JSONObject().put("bridge", prop("string", "Name oder ID; leer = diese Bridge"))
                .put("settings", prop("object", "zu ändernde Einstellungen")),
            listOf("settings")))
        .put(tool("eglo_bridge_command", "Befehl an eine Bridge: ${COMMANDS.joinToString()}.",
            JSONObject().put("bridge", prop("string", "Name oder ID; leer = diese Bridge"))
                .put("command", prop("string", "Befehl") { put("enum", JSONArray(COMMANDS)) }),
            listOf("command")))
        .put(tool("eglo_assign", "Legt fest, ob eine Bridge für eine Lampe (oder \"alle\") zuständig ist.",
            JSONObject().put("bridge", prop("string", "Name oder ID; leer = diese Bridge"))
                .put("lamp", prop("string", "Lampe oder \"alle\""))
                .put("responsible", prop("boolean", "zuständig ja/nein")),
            listOf("lamp", "responsible")))
        .put(tool("eglo_effects", "Musikmodus und gemeinsame Effekt-Einstellungen. Ohne Parameter: aktuellen Stand lesen.",
            JSONObject().put("music_mode", prop("boolean", "Musikmodus"))
                .put("music_source", prop("string", "Bridge, die per Mikrofon zuhört"))
                .put("music_sensitivity", prop("integer", "50–200 %"))
                .put("effect_speed", prop("integer", "0–100 %"))
                .put("fire_speed", prop("integer", "0–100 %"))
                .put("fire_strength", prop("integer", "0–100 %"))
                .put("fire_brightness", prop("integer", "5–100 %"))))
        .put(tool("eglo_log", "Letzte Log-Zeilen dieser Bridge.",
            JSONObject().put("lines", prop("integer", "Anzahl (Standard 50)")).put("contains", prop("string", "Filtertext"))))

    private fun mqtt(): MqttBridge = mqttProvider()?.takeIf { it.connected }
        ?: throw IllegalStateException("Bridge ist nicht mit MQTT verbunden")

    private fun norm(s: String) = s.lowercase().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        .replace("ß", "ss").replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun lamps(m: MqttBridge) = m.knownDevices().filter { it.isLight }

    private fun resolveLamp(m: MqttBridge, q: String): MeshDevice {
        val all = lamps(m)
        q.trim().toIntOrNull()?.let { id -> all.firstOrNull { it.meshId == id }?.let { return it } }
        val n = norm(q)
        all.filter { norm(it.name) == n }.singleOrNull()?.let { return it }
        val part = all.filter { norm(it.name).contains(n) }
        if (part.size == 1) return part[0]
        throw IllegalArgumentException(
            (if (part.isEmpty()) "Lampe „$q“ nicht gefunden" else "„$q“ ist nicht eindeutig") +
                ". Bekannt: " + all.joinToString { "${it.name.trim()} (${it.meshId})" })
    }

    private data class Br(val id: String, val name: String, val online: Boolean?)

    private fun bridgesOf(t: Map<String, String>): List<Br> {
        val ids = t.keys.mapNotNull { k ->
            if (k.startsWith("$B/bridge/")) k.removePrefix("$B/bridge/").substringBefore('/') else null
        }.toSortedSet()
        return ids.map { id ->
            val name = try { JSONObject(t["$B/bridge/$id/info"] ?: "{}").optString("name") } catch (_: Exception) { "" }
            Br(id, name.ifEmpty { id }, t["$B/bridge/$id/status"]?.let { it == "online" })
        }
    }

    private fun resolveBridge(m: MqttBridge, t: Map<String, String>, q: String?): Br {
        val all = bridgesOf(t)
        if (q.isNullOrBlank()) return all.firstOrNull { it.id == m.bridgeId } ?: Br(m.bridgeId, m.bridgeName, true)
        val n = norm(q)
        all.firstOrNull { it.id == q || norm(it.name) == n }?.let { return it }
        val part = all.filter { norm(it.name).contains(n) || it.id.contains(n.replace(' ', '_')) }
        if (part.size == 1) return part[0]
        throw IllegalArgumentException("Bridge „$q“ nicht eindeutig/nicht gefunden. Bekannt: " +
            all.joinToString { "${it.name} (${it.id})" })
    }

    private fun lampView(m: MqttBridge, t: Map<String, String>, d: MeshDevice, names: Map<String, String>): JSONObject {
        val st = t["$B/light/${d.meshId}/state"]?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        val o = JSONObject().put("id", d.meshId).put("name", d.name.trim())
            .put("available", t["$B/light/${d.meshId}/availability"]?.let { it == "online" } ?: JSONObject.NULL)
        if (st != null) {
            o.put("on", st.optString("state") == "ON")
            if (st.has("brightness")) o.put("brightness_pct", Math.round(st.optInt("brightness") / 2.55))
            o.put("color_mode", st.optString("color_mode"))
            if (st.optString("color_mode") == "color_temp" && st.has("color_temp")) o.put("color_temp_kelvin", st.optInt("color_temp"))
            st.optJSONObject("color")?.let { c -> if (st.optString("color_mode") == "rgb")
                o.put("rgb", String.format("#%02X%02X%02X", c.optInt("r"), c.optInt("g"), c.optInt("b"))) }
            st.optString("effect").takeIf { it.isNotEmpty() && it != "Kein Effekt" }?.let { o.put("effect", it) }
        }
        // owner: best link score among online bridges (same rule as the bridges)
        var best: String? = null
        var bestScore = 0
        val prefix = "$B/light/${d.meshId}/link/"
        for ((k, v) in t) if (k.startsWith(prefix)) {
            val bid = k.removePrefix(prefix)
            val sc = v.toIntOrNull() ?: 0
            if (sc <= 0 || t["$B/bridge/$bid/status"] == "offline") continue
            if (sc > bestScore || (sc == bestScore && best != null && bid < best)) { best = bid; bestScore = sc }
        }
        o.put("controlled_by", best?.let { names[it] ?: it } ?: JSONObject.NULL)
        return o
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(150)
        }
        return cond()
    }

    private fun callTool(name: String, a: JSONObject): Any {
        val m = mqtt()
        val t = m.topics()
        val names = bridgesOf(t).associate { it.id to it.name }
        return when (name) {
            "eglo_overview" -> JSONObject()
                .put("this_bridge", m.bridgeName)
                .put("bridges", JSONArray(bridgesOf(t).map { b ->
                    JSONObject().put("id", b.id).put("name", b.name).put("online", b.online ?: JSONObject.NULL)
                        .put("status", t["$B/bridge/${b.id}/state"] ?: JSONObject.NULL)
                }))
                .put("lamps", JSONArray(lamps(m).map { lampView(m, t, it, names) }))

            "eglo_set_light" -> {
                val list = a.optJSONArray("lamps") ?: throw IllegalArgumentException("lamps fehlt")
                val qs = (0 until list.length()).map { list.getString(it) }
                val targets = if (qs.size == 1 && norm(qs[0]) in listOf("alle", "all")) lamps(m) else qs.map { resolveLamp(m, it) }
                val cmd = JSONObject()
                if (a.has("on") && !a.optBoolean("on")) cmd.put("state", "OFF") else {
                    cmd.put("state", "ON")
                    if (a.has("brightness_pct")) cmd.put("brightness", Math.round(a.optDouble("brightness_pct").coerceIn(1.0, 100.0) * 2.55).toInt())
                    if (a.has("color_temp_kelvin")) cmd.put("color_temp", a.optInt("color_temp_kelvin").coerceIn(2700, 6500))
                    if (a.has("rgb")) {
                        val h = a.optString("rgb").removePrefix("#")
                        require(h.length == 6) { "rgb als #RRGGBB angeben" }
                        cmd.put("color", JSONObject().put("r", h.substring(0, 2).toInt(16))
                            .put("g", h.substring(2, 4).toInt(16)).put("b", h.substring(4, 6).toInt(16)))
                    }
                    if (a.has("effect")) cmd.put("effect", a.optString("effect"))
                }
                val before = targets.associate { it.meshId to t["$B/light/${it.meshId}/state"] }
                targets.forEach { m.send("$B/light/${it.meshId}/set", cmd.toString()) }
                waitFor(4000) { val now = m.topics(); targets.all { now["$B/light/${it.meshId}/state"] != before[it.meshId] } }
                val now = m.topics()
                JSONObject().put("sent", cmd).put("lamps", JSONArray(targets.map { lampView(m, now, it, names) }))
            }

            "eglo_bridge_settings" -> {
                val b = resolveBridge(m, t, a.optString("bridge"))
                val cfg = t["$B/bridge/${b.id}/config"]?.let { JSONObject(it) }
                    ?: JSONObject().put("note", "Diese Bridge meldet keine Einstellungen (Version zu alt).")
                cfg.put("online", b.online ?: JSONObject.NULL).put("status", t["$B/bridge/${b.id}/state"] ?: JSONObject.NULL)
            }

            "eglo_configure_bridge" -> {
                val b = resolveBridge(m, t, a.optString("bridge"))
                val s = a.optJSONObject("settings") ?: throw IllegalArgumentException("settings fehlt")
                if (s.has("screen_on")) {
                    m.send("$B/bridge/${b.id}/screen/set", if (s.optBoolean("screen_on")) "ON" else "OFF")
                    s.remove("screen_on")
                }
                val before = t["$B/bridge/${b.id}/config"]
                if (s.length() > 0) m.send("$B/bridge/${b.id}/config/set", s.toString())
                val slow = listOf("name", "restart", "reimport", "mqtt_host", "mqtt_port", "mqtt_user", "mqtt_password").any { s.has(it) }
                val ok = s.length() == 0 || waitFor(if (slow) 25000 else 5000) { m.topics()["$B/bridge/${b.id}/config"] != before }
                val after = m.topics()["$B/bridge/${b.id}/config"]?.let { JSONObject(it).optJSONObject("settings") }
                JSONObject().put("bridge", b.name).put("confirmed", ok)
                    .put("sent", JSONArray(s.keys().asSequence().toList()))
                    .put("settings", after ?: JSONObject.NULL)
            }

            "eglo_bridge_command" -> {
                val b = resolveBridge(m, t, a.optString("bridge"))
                val c = a.optString("command")
                require(c in COMMANDS) { "Unbekannter Befehl $c" }
                if (c == "install_update") m.send("$B/bridge/${b.id}/update/install", "install")
                else m.send("$B/bridge/${b.id}/cmd", if (c == "reload_kiosk") "reload" else c)
                JSONObject().put("bridge", b.name).put("command", c).put("sent", true)
            }

            "eglo_assign" -> {
                val b = resolveBridge(m, t, a.optString("bridge"))
                val on = if (a.optBoolean("responsible")) "ON" else "OFF"
                val lq = a.optString("lamp")
                if (norm(lq) in listOf("alle", "all")) m.send("$B/bridge/${b.id}/lamps_all/set", on)
                else m.send("$B/bridge/${b.id}/lamp/${resolveLamp(m, lq).meshId}/set", on)
                Thread.sleep(1500)
                val now = m.topics()
                JSONObject().put("bridge", b.name)
                    .put("all_lamps", now["$B/bridge/${b.id}/lamps_all"] ?: JSONObject.NULL)
                    .put("lamps", JSONObject().apply {
                        lamps(m).forEach { d -> now["$B/bridge/${b.id}/lamp/${d.meshId}"]?.let { put(d.name.trim(), it) } }
                    })
            }

            "eglo_effects" -> {
                if (a.has("music_source")) m.send("$B/music/source/set", resolveBridge(m, t, a.optString("music_source")).name)
                if (a.has("music_sensitivity")) m.send("$B/music_sensitivity/set", a.optInt("music_sensitivity").toString())
                for (k in listOf("effect_speed", "fire_speed", "fire_strength", "fire_brightness"))
                    if (a.has(k)) m.send("$B/$k/set", a.optInt(k).toString())
                if (a.has("music_mode")) m.send("$B/music/set", if (a.optBoolean("music_mode")) "ON" else "OFF")
                if (a.length() > 0) Thread.sleep(1200)
                val now = m.topics()
                JSONObject().apply {
                    for ((k, topic) in listOf("music_mode" to "$B/music", "music_source" to "$B/music/source_name",
                        "music_sensitivity" to "$B/music_sensitivity", "effect_speed" to "$B/effect_speed",
                        "fire_speed" to "$B/fire_speed", "fire_strength" to "$B/fire_strength", "fire_brightness" to "$B/fire_brightness"))
                        now[topic]?.let { put(k, it) }
                }
            }

            "eglo_log" -> {
                val n = a.optInt("lines", 50).coerceIn(1, 400)
                val f = a.optString("contains").lowercase()
                JSONArray(BridgeLog.snapshot().filter { f.isEmpty() || it.lowercase().contains(f) }.takeLast(n))
            }

            else -> throw IllegalArgumentException("Unbekanntes Werkzeug $name")
        }
    }
}
