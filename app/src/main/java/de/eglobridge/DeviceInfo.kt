package de.eglobridge

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** Facts about the tablet itself, published (retained) for Home Assistant and assistants. */
object DeviceInfo {

    fun collect(ctx: Context): JSONObject {
        val o = JSONObject()
        fun put(k: String, f: () -> Any?) {
            try { f()?.let { o.put(k, it) } } catch (_: Throwable) { }
        }
        put("manufacturer") { Build.MANUFACTURER }
        put("model") { Build.MODEL }
        put("device") { Build.DEVICE }
        put("android") { Build.VERSION.RELEASE }
        put("sdk") { Build.VERSION.SDK_INT }
        put("rom") { sysProp("ro.lineage.version") ?: sysProp("ro.build.display.id") }
        put("app_version") { pkgVersion(ctx, ctx.packageName) }
        put("webview") { webView(ctx) }
        put("ip") { ipAddress() }
        put("wifi_rssi") {
            @Suppress("DEPRECATION")
            (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.rssi
        }
        put("uptime_h") { Math.round(SystemClock.elapsedRealtime() / 360000.0) / 10.0 }
        put("storage_free_mb") {
            val s = StatFs(Environment.getDataDirectory().path)
            s.availableBytes / (1024 * 1024)
        }
        put("ram_free_mb") {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            mi.availMem / (1024 * 1024)
        }
        put("bluetooth_on") {
            @Suppress("DEPRECATION")
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled
        }
        put("media_volume") { mediaVolume(ctx) }
        return o
    }

    fun mediaVolume(ctx: Context): Int {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)
    }

    fun setMediaVolume(ctx: Context, percent: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(percent.coerceIn(0, 100) * max / 100f), 0)
    }

    private fun pkgVersion(ctx: Context, pkg: String): String? = try {
        ctx.packageManager.getPackageInfo(pkg, 0).versionName
    } catch (_: Exception) { null }

    private fun webView(ctx: Context): String? {
        if (Build.VERSION.SDK_INT >= 26) {
            android.webkit.WebView.getCurrentWebViewPackage()?.let { return "${it.packageName} ${it.versionName}" }
        }
        for (p in listOf("com.google.android.webview", "com.android.webview", "org.lineageos.webview")) {
            pkgVersion(ctx, p)?.let { return "$p $it" }
        }
        return null
    }

    private fun ipAddress(): String? {
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
        }
        return null
    }

    private fun sysProp(key: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, key) as String).ifEmpty { null }
    } catch (_: Throwable) { null }
}
