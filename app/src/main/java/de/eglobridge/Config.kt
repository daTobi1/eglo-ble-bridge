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
    @android.annotation.SuppressLint("HardwareIds")
    private val androidId: String = try {
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
    } catch (_: Throwable) { "" }

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

    /** Name of this tablet (shown in Home Assistant, used for its MQTT id). */
    var bridgeName: String
        get() = p.getString("bridgeName", null)
            // several identical tablets must not share a name (it is their MQTT id)
            ?: ("Tablet " + android.os.Build.MODEL + " " + androidId.takeLast(4).uppercase()).trim()
        set(v) = p.edit().putString("bridgeName", v.trim()).apply()

    var debugLog: Boolean
        get() = p.getBoolean("debugLog", false)
        set(v) = p.edit().putBoolean("debugLog", v).apply()
    var musicSensitivity: Float
        get() = p.getFloat("musicSensitivity", 1.0f)
        set(v) = p.edit().putFloat("musicSensitivity", v).apply()

    /** Mesh ids taking part in the music mode; null = all lamps. */
    var musicSelection: Set<Int>?
        get() = p.getString("musicSelection", null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
        set(v) = p.edit().putString("musicSelection", v?.joinToString(",")).apply()

    // ---- kiosk
    var kioskEnabled: Boolean
        get() = p.getBoolean("kioskEnabled", false)
        set(v) = p.edit().putBoolean("kioskEnabled", v).apply()
    var kioskUrl: String
        get() = p.getString("kioskUrl", null) ?: "http://$mqttHost:8123/"
        set(v) = p.edit().putString("kioskUrl", v.trim()).apply()
    var kioskPin: String
        get() = p.getString("kioskPin", "1234")!!
        set(v) = p.edit().putString("kioskPin", v.trim()).apply()
    /** Minutes without touch until the screen goes dark; 0 = never. */
    var kioskScreenTimeout: Int
        get() = p.getInt("kioskScreenTimeout", 0)
        set(v) = p.edit().putInt("kioskScreenTimeout", v).apply()
    var kioskBrightness: Int
        get() = p.getInt("kioskBrightness", 80)
        set(v) = p.edit().putInt("kioskBrightness", v).apply()

    /** Android display timeout to restore after the kiosk switched the screen off (ms). */
    var savedScreenTimeout: Int
        get() = p.getInt("savedScreenTimeout", 0)
        set(v) = p.edit().putInt("savedScreenTimeout", v).apply()

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
