package de.eglobridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class MeshDevice(
    val meshId: Int,
    val name: String,
    val mac: String,
    val model: String,
    val vendor: String,
    val firmware: String,
    val type: String
) {
    val isLight get() = type.contains("light")
    val hasColor get() = type.contains("color")
    val hasTemperature get() = type.contains("temperature")
    val hasDimming get() = type.contains("dimming")

    fun toJson(): JSONObject = JSONObject()
        .put("meshId", meshId).put("name", name).put("mac", mac).put("model", model)
        .put("vendor", vendor).put("firmware", firmware).put("type", type)

    companion object {
        fun fromJson(o: JSONObject) = MeshDevice(
            o.getInt("meshId"), o.optString("name"), o.optString("mac"), o.optString("model"),
            o.optString("vendor"), o.optString("firmware"), o.optString("type")
        )

        /** Normalise MAC to upper case with colons. */
        fun normalizeMac(raw: String): String {
            val hex = raw.replace(":", "").replace("-", "").uppercase()
            if (hex.length != 12) return raw.uppercase()
            return hex.chunked(2).joinToString(":")
        }
    }
}

class Config(context: Context) {
    private val p = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    var awoxUser: String
        get() = p.getString("awoxUser", "")!!
        set(v) = p.edit().putString("awoxUser", v).apply()
    var awoxPassword: String
        get() = p.getString("awoxPassword", "")!!
        set(v) = p.edit().putString("awoxPassword", v).apply()

    var meshName: String
        get() = p.getString("meshName", "")!!
        set(v) = p.edit().putString("meshName", v).apply()
    var meshPassword: String
        get() = p.getString("meshPassword", "")!!
        set(v) = p.edit().putString("meshPassword", v).apply()

    var mqttHost: String
        get() = p.getString("mqttHost", "192.168.178.120")!!
        set(v) = p.edit().putString("mqttHost", v).apply()
    var mqttPort: Int
        get() = p.getInt("mqttPort", 1883)
        set(v) = p.edit().putInt("mqttPort", v).apply()
    var mqttUser: String
        get() = p.getString("mqttUser", "")!!
        set(v) = p.edit().putString("mqttUser", v).apply()
    var mqttPassword: String
        get() = p.getString("mqttPassword", "")!!
        set(v) = p.edit().putString("mqttPassword", v).apply()

    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    var devices: List<MeshDevice>
        get() {
            val raw = p.getString("devices", "[]")!!
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { MeshDevice.fromJson(arr.getJSONObject(it)) }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(it.toJson()) }
            p.edit().putString("devices", arr.toString()).apply()
        }

    val isComplete: Boolean
        get() = meshName.isNotEmpty() && meshPassword.isNotEmpty() && devices.isNotEmpty() && mqttHost.isNotEmpty()
}
