package de.eglobridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager

class BridgeService : Service(), MeshListener {

    companion object {
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
        @Volatile var running = false
        @Volatile var meshState = "gestoppt"

        fun start(ctx: Context) {
            val i = Intent(ctx, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BridgeService::class.java))
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var mesh: MeshManager? = null
    private var mqtt: MqttBridge? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val logListener: (String) -> Unit = { line -> handler.post { mqtt?.onLog(line) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("bridge").also { it.start() }
        handler = Handler(thread.looper)
        createChannel()
        startForeground(NOTIF_ID, buildNotification("startet …"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mesh == null) startBridge()
        return START_STICKY
    }

    private fun startBridge() {
        val cfg = Config(this)
        if (!cfg.isComplete) {
            BridgeLog.e("Konfiguration unvollständig – bitte in der App ausfüllen und Geräte importieren")
            stopSelf()
            return
        }
        running = true
        acquireLocks()
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
        BridgeLog.i("EGLO Bridge $version gestartet")
        val m = MeshManager(this, handler, this)
        mesh = m
        mqtt = MqttBridge(handler, m, version).also { it.start(cfg) }
        BridgeLog.listeners.add(logListener)
        m.start(cfg)
    }

    override fun onDestroy() {
        running = false
        meshState = "gestoppt"
        BridgeLog.listeners.remove(logListener)
        BridgeLog.i("Bridge gestoppt")
        mesh?.stop()
        mqtt?.markAllOffline()
        mqtt?.stop()
        mesh = null
        mqtt = null
        handler.postDelayed({ thread.quitSafely() }, 1000)
        releaseLocks()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- MeshListener (bridge thread)

    override fun onMeshState(state: String) {
        meshState = state
        mqtt?.onBridgeState(state)
        updateNotification(state)
        if (!state.startsWith("verbunden")) mqtt?.markAllOffline()
    }

    override fun onStatus(status: LightStatus) {
        mqtt?.onStatus(status)
    }

    override fun onDeviceOffline(meshId: Int) {
        mqtt?.onDeviceOffline(meshId)
    }

    // ---------------------------------------------------------------- helpers

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eglobridge:bridge").also {
            it.setReferenceCounted(false); it.acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "eglobridge:wifi").also {
            it.setReferenceCounted(false); it.acquire()
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (_: Throwable) { }
        try { wifiLock?.release() } catch (_: Throwable) { }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "EGLO Bridge", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setContentTitle("EGLO Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
