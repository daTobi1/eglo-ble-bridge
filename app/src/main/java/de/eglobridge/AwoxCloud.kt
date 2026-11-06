package de.eglobridge

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Minimal client for the AwoX/EGLO Parse cloud, used once to import the mesh
 * credentials and the device list (same endpoints as fsaris/home-assistant-awox).
 * Must be called from a background thread.
 */
class AwoxCloud(private val username: String, private val password: String) {

    companion object {
        private const val CONNECT_URL = "https://l4hparse-prod.awox.cloud/parse/"
        private const val HOME_CONTROL_URL = "https://l4hparse-hc-prod.awox.cloud/parse/"
        private const val APP_ID = "55O69FLtoxPt67LLwaHGpHmVWndhZGn9Wty8PLrJ"
        private const val CLIENT_KEY = "PyR3yV65rytEicteNlQHSVNpAGvCByOrsLiEqJtI"
    }

    data class Result(val meshName: String, val meshPassword: String, val devices: List<MeshDevice>)

    private val installationId = UUID.randomUUID().toString()
    private var apiUrl = CONNECT_URL
    private var objectId = ""
    private var sessionToken = ""

    private fun post(url: String, body: JSONObject, session: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.doOutput = true
        c.setRequestProperty("x-parse-application-id", APP_ID)
        c.setRequestProperty("x-parse-installation-id", installationId)
        c.setRequestProperty("x-parse-client-key", CLIENT_KEY)
        c.setRequestProperty("content-type", "application/json")
        if (session != null) c.setRequestProperty("x-parse-session-token", session)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }

    private fun login() {
        val body = JSONObject().put("username", username.lowercase().trim()).put("password", password).put("_method", "GET")
        var (code, text) = post(CONNECT_URL + "login", body)
        if (code != 200) {
            apiUrl = HOME_CONTROL_URL
            val r = post(HOME_CONTROL_URL + "login", body)
            code = r.first; text = r.second
            if (code != 200) {
                val err = try { JSONObject(text).optString("error", text) } catch (_: Exception) { text }
                throw Exception("Login fehlgeschlagen: $err")
            }
        }
        val o = JSONObject(text)
        objectId = o.getString("objectId")
        sessionToken = o.getString("sessionToken")
    }

    private fun fetchClass(name: String): JSONArray {
        val where = JSONObject().put(
            "owner", JSONObject().put("__type", "Pointer").put("className", "_User").put("objectId", objectId)
        )
        val (code, text) = post(apiUrl + "classes/" + name, JSONObject().put("where", where).put("_method", "GET"), sessionToken)
        if (code != 200) throw Exception("Laden von $name fehlgeschlagen ($code): $text")
        return JSONObject(text).getJSONArray("results")
    }

    fun load(): Result {
        login()
        val creds = fetchClass("Credential")
        var meshName = ""
        var meshPassword = ""
        for (i in 0 until creds.length()) {
            val c = creds.getJSONObject(i)
            if (c.optString("service") == "mesh") {
                meshName = c.optString("client_id")
                meshPassword = c.optString("access_token")
                break
            }
        }
        if (meshName.isEmpty()) throw Exception("Keine Mesh-Zugangsdaten im AwoX-Konto gefunden")

        val devs = fetchClass("Device")
        val list = mutableListOf<MeshDevice>()
        for (i in 0 until devs.length()) {
            val d = devs.getJSONObject(i)
            val type = d.optString("type")
            if (!type.startsWith(".ble.tlmesh")) continue
            val address = d.optString("address")
            val mac = d.optString("macAddress")
            if (address.isEmpty() || mac.isEmpty()) continue
            list += MeshDevice(
                meshId = address.toInt(),
                name = d.optString("displayName", "EGLO $address"),
                mac = MeshDevice.normalizeMac(mac),
                model = d.optString("modelName", "unknown"),
                vendor = d.optString("vendor", "EGLO"),
                firmware = d.optString("version", ""),
                type = type
            )
        }
        return Result(meshName, meshPassword, list)
    }
}
