package de.eglobridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the GitHub release "latest" of the repository.
 * The CI publishes eglo-ble-bridge.apk and version.json there.
 */
object Updater {
    private const val BASE = "https://github.com/daTobi1/eglo-ble-bridge/releases/download/latest/"
    private const val VERSION_URL = BASE + "version.json"
    private const val APK_URL = BASE + "eglo-ble-bridge.apk"
    const val ACTION_INSTALL_STATUS = "de.eglobridge.INSTALL_STATUS"

    data class Info(val versionCode: Int, val versionName: String, val notes: String)

    @Volatile var latest: Info? = null
    @Volatile var lastCheck = 0L
    @Volatile var status: String = ""

    fun currentVersionCode(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        // follow redirects manually (GitHub -> objects.githubusercontent.com)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "eglo-ble-bridge")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw Exception("Redirect ohne Ziel")
                c.disconnect()
                u = URL(u, loc)
            } else if (code == 200) {
                return c
            } else {
                c.disconnect()
                throw Exception("HTTP $code für $u")
            }
        }
        throw Exception("Zu viele Weiterleitungen")
    }

    /** Blocking; returns the newer version or null if up to date. */
    fun check(ctx: Context): Info? {
        val c = open(VERSION_URL)
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val o = JSONObject(text)
        val info = Info(o.getInt("versionCode"), o.optString("versionName"), o.optString("notes"))
        lastCheck = System.currentTimeMillis()
        latest = info
        return if (info.versionCode > currentVersionCode(ctx)) info else null
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Blocking: downloads the APK and hands it to the system installer (user confirms once). */
    fun downloadAndInstall(ctx: Context, progress: (String) -> Unit) {
        progress("Lade Update herunter …")
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            val c = open(APK_URL)
            val total = c.contentLengthLong.takeIf { it > 0 } ?: -1L
            session.openWrite("base.apk", 0, total).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct && pct % 5 == 0) { lastPct = pct; progress("Lade Update … $pct %") }
                        }
                    }
                    session.fsync(out)
                }
            }
            c.disconnect()
            progress("Installiere …")
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(pi.intentSender)
        } catch (t: Throwable) {
            session.abandon()
            throw t
        } finally {
            session.close()
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
                Updater.status = "Bitte Installation bestätigen"
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Updater.status = "Update installiert"
                BridgeLog.i("Update installiert")
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Status $st"
                Updater.status = "Update fehlgeschlagen: $msg"
                BridgeLog.e("Update fehlgeschlagen: $msg")
            }
        }
    }
}
