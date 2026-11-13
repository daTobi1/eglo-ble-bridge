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
        /** Access for the in-app light controls while the service runs. */
        @Volatile var controller: LightController? = null
        @Volatile var hub: MqttBridge? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BridgeService::class.java))
        }

        /**
         * Called by the activities whenever they come to the front. Android 11 only lets a foreground
         * service use the microphone if it was started while the app was visible; after a reboot the
         * service starts in the background and Android hands it silence. Starting it again from a
         * visible activity upgrades its microphone access, then the microphone is reopened.
         */
        fun refreshFromForeground(ctx: Context) {
            if (!running) return
            start(ctx)
            MicHub.reopenLater()
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var mesh: MeshManager? = null
    private var mqtt: MqttBridge? = null
    private var lights: LightController? = null
    private var voice: VoiceSatellite? = null
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

    /** MCP endpoint for assistants (port 8765); uses whatever MQTT connection the bridge has. */
    private val mcp = McpServer({ hub })

    private var notifText = "startet …"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // every start request must be answered with startForeground (also when already running)
        startForeground(NOTIF_ID, buildNotification(notifText))
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
        BridgeLog.verbose = cfg.debugLog
        val m = MeshManager(this, handler, this)
        mesh = m
        val lc = LightController(handler, m, cfg.devices)
        lc.musicSensitivity = cfg.musicSensitivity
        lights = lc
        controller = lc
        val name = cfg.bridgeName
        mqtt = MqttBridge(handler, m, lc, version, cfg.bridgeId, name).also {
            hub = it
            it.onServiceCommand = { cmd -> handler.post { onServiceCommand(cmd) } }
            it.onConfigSet = { json -> handler.post { onConfigSet(json) } }
            it.deviceInfo = { DeviceInfo.collect(this) }
            if (cfg.mcpEnabled) mcp.start()
            it.hasMicPermission = {
                Build.VERSION.SDK_INT < 23 ||
                    checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            it.start(cfg)
        }
        BridgeLog.listeners.add(logListener)
        MicHub.source = cfg.micSource
        if (cfg.voiceEnabled) voice = VoiceSatellite(this, version).also { it.start() }
        m.start(cfg)
        handler.removeCallbacks(updateCheck)
        handler.postDelayed(updateCheck, 30 * 1000L)
        handler.removeCallbacks(batteryCheck)
        handler.postDelayed(batteryCheck, 5000L)
    }

    private val batteryCheck = object : Runnable {
        override fun run() {
            try {
                val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (i != null) {
                    val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                    val status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    if (level >= 0) mqtt?.publishBattery(level * 100 / scale.coerceAtLeast(1), charging)
                }
            } catch (_: Throwable) { }
            handler.postDelayed(this, 60 * 1000L)
        }
    }

    /** Tear down mesh + MQTT (keeps the service and its thread alive). */
    private fun stopBridge() {
        voice?.stop()
        voice = null
        mcp.stop()
        BridgeLog.listeners.remove(logListener)
        handler.removeCallbacks(updateCheck)
        handler.removeCallbacks(batteryCheck)
        controller = null
        hub = null
        lights?.shutdown()
        mesh?.stop()
        lights?.markAllOffline()
        mqtt?.stop()
        mesh = null
        mqtt = null
        lights = null
    }

    /**
     * Settings sent over MQTT (`eglo_bridge/bridge/<id>/config/set`, JSON). Unknown keys are ignored.
     * MQTT changes are only saved after a test connection with the new values worked, so a typo
     * cannot cut the tablet off.
     */
    private fun onConfigSet(o: org.json.JSONObject) {
        val cfg = Config(this)
        val changed = mutableListOf<String>()
        var restart = false
        val main = android.os.Handler(android.os.Looper.getMainLooper())

        o.optString("name").trim().takeIf { it.isNotEmpty() && it != cfg.bridgeName }?.let {
            cfg.bridgeName = it; changed += "Name „$it“"; restart = true
        }
        if (o.has("debug_log")) {
            cfg.debugLog = o.optBoolean("debug_log"); BridgeLog.verbose = cfg.debugLog; changed += "Debug-Log"
        }
        if (o.has("kiosk_url")) o.optString("kiosk_url").trim().takeIf { it.isNotEmpty() }?.let { u ->
            cfg.kioskUrl = u; main.post { KioskControl.ui?.loadUrl(cfg.kioskUrl) }; changed += "Dashboard-Adresse"
        }
        if (o.has("kiosk_pin")) o.optString("kiosk_pin").trim().takeIf { it.isNotEmpty() }?.let {
            cfg.kioskPin = it; changed += "Kiosk-PIN"
        }
        if (o.has("kiosk_screen_timeout")) {
            cfg.kioskScreenTimeout = o.optInt("kiosk_screen_timeout").coerceIn(0, 120); changed += "Bildschirm-Timeout"
        }
        if (o.has("kiosk_brightness")) {
            val b = o.optInt("kiosk_brightness").coerceIn(1, 100)
            cfg.kioskBrightness = b; main.post { KioskControl.ui?.setBrightness(b) }; changed += "Helligkeit"
        }
        if (o.has("kiosk_enabled")) {
            val on = o.optBoolean("kiosk_enabled")
            if (on != cfg.kioskEnabled || (on && KioskControl.ui == null)) {
                changed += if (on) "Kiosk an" else "Kiosk aus"
                if (on) {
                    cfg.kioskEnabled = true
                    main.post {
                        try {
                            startActivity(Intent(this, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (t: Throwable) { BridgeLog.w("Kiosk konnte nicht gestartet werden: ${t.message}") }
                    }
                } else {
                    cfg.kioskEnabled = false
                    main.post { KioskControl.ui?.exit() }
                }
            }
        }
        if (o.has("voice_enabled")) {
            val on = o.optBoolean("voice_enabled")
            if (on != cfg.voiceEnabled) {
                cfg.voiceEnabled = on
                changed += if (on) "Sprach-Satellit an" else "Sprach-Satellit aus"
                restart = true
            }
        }
        if (o.has("mic_source")) {
            val src = o.optString("mic_source").trim().lowercase()
            if (src != cfg.micSource) {
                cfg.micSource = src; changed += "Mikrofon-Quelle ${cfg.micSource}"; restart = true
            }
        }
        if (o.has("voice_noise_suppression")) {
            cfg.voiceNoiseSuppression = o.optInt("voice_noise_suppression"); changed += "Rauschunterdrückung ${cfg.voiceNoiseSuppression}"
        }
        if (o.has("voice_auto_gain")) {
            cfg.voiceAutoGain = o.optInt("voice_auto_gain"); changed += "Auto-Verstärkung ${cfg.voiceAutoGain} dBFS"
        }
        if (o.has("voice_volume_multiplier")) {
            cfg.voiceVolumeMultiplier = o.optDouble("voice_volume_multiplier", 1.0).toFloat()
            changed += "Mikrofon-Verstärkung ×${cfg.voiceVolumeMultiplier}"
        }
        if (o.has("media_volume")) {
            DeviceInfo.setMediaVolume(this, o.optInt("media_volume")); changed += "Lautstärke"
        }
        if (o.has("awox_user") || o.has("awox_password")) {
            o.optString("awox_user").trim().takeIf { it.isNotEmpty() }?.let { cfg.awoxUser = it }
            o.optString("awox_password").takeIf { it.isNotEmpty() }?.let { cfg.awoxPassword = it }
            changed += "AwoX-Konto"
        }
        if (changed.isNotEmpty()) BridgeLog.i("Einstellungen geändert: " + changed.joinToString(", "))

        val mqttKeys = listOf("mqtt_host", "mqtt_port", "mqtt_user", "mqtt_password")
        if (mqttKeys.any { o.has(it) }) {
            val host = o.optString("mqtt_host", cfg.mqttHost).trim().ifEmpty { cfg.mqttHost }
            val port = o.optInt("mqtt_port", cfg.mqttPort)
            val user = if (o.has("mqtt_user")) o.optString("mqtt_user").trim() else cfg.mqttUser
            val pass = if (o.has("mqtt_password")) o.optString("mqtt_password") else cfg.mqttPassword
            Thread {
                if (MqttBridge.testConnection(host, port, user, pass)) {
                    cfg.mqttHost = host; cfg.mqttPort = port; cfg.mqttUser = user; cfg.mqttPassword = pass
                    BridgeLog.i("MQTT-Zugang geändert ($host:$port) – Bridge startet neu")
                    handler.post { onServiceCommand("restart") }
                } else {
                    BridgeLog.e("MQTT-Änderung verworfen: Testverbindung zu $host:$port fehlgeschlagen")
                }
            }.start()
        }

        if (o.optBoolean("reimport")) onServiceCommand("reimport")
        else if (restart || o.optBoolean("restart")) onServiceCommand("restart")
        else mqtt?.publishConfig()
    }

    /** Commands that arrive from Home Assistant (the tablets run in kiosk mode). */
    private fun onServiceCommand(cmd: String) {
        when (cmd) {
            "restart" -> {
                BridgeLog.i("Neustart der Bridge (aus Home Assistant)")
                stopBridge()
                // give the old MQTT session time to say goodbye before the new one says hello
                handler.postDelayed({ if (mesh == null) startBridge() }, 4000)
            }
            "reimport" -> Thread {
                val cfg = Config(this)
                try {
                    BridgeLog.i("Lampen werden neu aus der AwoX-Cloud importiert …")
                    val r = AwoxCloud(cfg.awoxUser, cfg.awoxPassword).load()
                    cfg.meshName = r.meshName
                    cfg.meshPassword = r.meshPassword
                    cfg.devices = r.devices
                    BridgeLog.i("Import: ${r.devices.size} Geräte – Bridge startet neu")
                    handler.post { onServiceCommand("restart") }
                } catch (t: Throwable) {
                    BridgeLog.e("Import fehlgeschlagen", t)
                }
            }.start()
            "check_update" -> handler.post(updateCheck)
            "install_update" -> Thread {
                try {
                    if (!Updater.canInstall(this)) {
                        BridgeLog.e("Update: Installation aus dieser App ist auf dem Tablet nicht erlaubt " +
                            "(einmalig in der App „Update installieren“ antippen und erlauben)")
                        return@Thread
                    }
                    Updater.downloadAndInstall(this) { msg ->
                        BridgeLog.i("Update: $msg")
                        handler.post { mqtt?.publishUpdate(inProgress = true) }
                    }
                    BridgeLog.i("Update: bitte auf dem Tablet „Installieren“ bestätigen")
                } catch (t: Throwable) {
                    BridgeLog.e("Update-Download fehlgeschlagen", t)
                    handler.post { mqtt?.publishUpdate(inProgress = false) }
                }
            }.start()
        }
    }

    private val updateCheck = object : Runnable {
        override fun run() {
            Thread {
                try {
                    val info = Updater.check(this@BridgeService)
                    if (info != null) {
                        BridgeLog.i("Update verfügbar: ${info.versionName} (Build ${info.versionCode})")
                        notifyUpdate(info)
                        // in kiosk mode the update window appears on the dashboard
                        android.os.Handler(android.os.Looper.getMainLooper()).post { KioskControl.ui?.showUpdate(info) }
                    }
                    handler.post { mqtt?.publishUpdate(inProgress = false) }
                } catch (t: Throwable) {
                    BridgeLog.w("Update-Prüfung fehlgeschlagen: ${t.message}")
                }
            }.start()
            handler.removeCallbacks(this)
            handler.postDelayed(this, 30 * 60 * 1000L)
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyUpdate(info: Updater.Info) {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val n = b.setContentTitle("EGLO Bridge: Update verfügbar")
            .setContentText("Version ${info.versionName} – zum Installieren tippen")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2, n)
    }

    override fun onDestroy() {
        running = false
        meshState = "gestoppt"
        BridgeLog.i("Bridge gestoppt")
        stopBridge()
        handler.postDelayed({ thread.quitSafely() }, 1000)
        releaseLocks()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- MeshListener (bridge thread)

    override fun onMeshState(state: String) {
        meshState = state
        mqtt?.onBridgeState(state)
        updateNotification(state)
        if (!state.startsWith("verbunden")) lights?.markAllOffline()
    }

    override fun onStatus(status: LightStatus) {
        lights?.onStatus(status)
    }

    override fun onDeviceOffline(meshId: Int) {
        lights?.setAvailable(meshId, false)
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
        notifText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
