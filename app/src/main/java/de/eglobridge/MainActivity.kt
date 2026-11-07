package de.eglobridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        const val EXTRA_FROM_KIOSK = "from_kiosk"
    }

    private lateinit var cfg: Config
    private lateinit var status: TextView
    private lateinit var devicesView: TextView
    private lateinit var logView: TextView
    private lateinit var awoxUser: EditText
    private lateinit var awoxPassword: EditText
    private lateinit var mqttHost: EditText
    private lateinit var mqttPort: EditText
    private lateinit var mqttUser: EditText
    private lateinit var mqttPassword: EditText
    private lateinit var updateStatus: TextView
    private lateinit var btnInstallUpdate: Button
    private lateinit var lampsBox: LinearLayout
    private lateinit var btnMusic: Button
    private val lampPanels = LinkedHashMap<Int, LampPanel>()
    private val musicChecks = LinkedHashMap<Int, CheckBox>()
    private lateinit var musicSource: android.widget.Spinner
    private lateinit var bridgeName: EditText
    private lateinit var kioskUrl: EditText
    private lateinit var kioskPin: EditText
    private lateinit var kioskEnabled: CheckBox
    private lateinit var fireSpeed: SeekBar
    private lateinit var fireStrength: SeekBar
    private lateinit var fireBrightness: SeekBar
    private var fireTouched = 0L
    private var sourceNames: List<String> = emptyList()
    private var updateBusy = false
    private var promptedFor = -1
    private var updateMsg = ""

    private val ui = Handler(Looper.getMainLooper())
    private var autostartOnly = false
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            autostartOnly = true
            // started by BootReceiver: start the bridge while we are in the foreground,
            // then get out of the way of the kiosk app
            BridgeService.start(this)
            Handler(Looper.getMainLooper()).postDelayed({ moveTaskToBack(true); finish() }, 2500)
            return
        }
        setContentView(R.layout.activity_main)
        cfg = Config(this)

        status = findViewById(R.id.status)
        devicesView = findViewById(R.id.devices)
        logView = findViewById(R.id.log)
        awoxUser = findViewById(R.id.awoxUser)
        awoxPassword = findViewById(R.id.awoxPassword)
        mqttHost = findViewById(R.id.mqttHost)
        mqttPort = findViewById(R.id.mqttPort)
        mqttUser = findViewById(R.id.mqttUser)
        mqttPassword = findViewById(R.id.mqttPassword)
        updateStatus = findViewById(R.id.updateStatus)
        lampsBox = findViewById(R.id.lampsBox)
        btnMusic = findViewById(R.id.btnMusic)
        btnInstallUpdate = findViewById(R.id.btnInstallUpdate)

        awoxUser.setText(cfg.awoxUser)
        awoxPassword.setText(cfg.awoxPassword)
        mqttHost.setText(cfg.mqttHost)
        mqttPort.setText(cfg.mqttPort.toString())
        mqttUser.setText(cfg.mqttUser)
        mqttPassword.setText(cfg.mqttPassword)
        showDevices()

        findViewById<Button>(R.id.btnImport).setOnClickListener { importFromCloud() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { saveAndStart() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            cfg.enabled = false
            BridgeService.stop(this)
        }
        findViewById<Button>(R.id.btnPerms).setOnClickListener { requestPerms() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnMic).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            else Toast.makeText(this, "Mikrofon ist erlaubt", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else Toast.makeText(this, "Einblenden ist erlaubt", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnInstallPerm).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } else Toast.makeText(this, "Installieren ist erlaubt", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("EGLO Bridge Log", BridgeLog.snapshot().joinToString("\n")))
            Toast.makeText(this, "Log kopiert", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkUpdate(manual = true) }
        buildLampPanels()
        btnMusic.setOnClickListener { toggleMusic() }
        val sens = findViewById<SeekBar>(R.id.musicSensitivity)
        sens.progress = ((cfg.musicSensitivity - 0.5f) * 100).toInt().coerceIn(0, 150)
        sens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val v = 0.5f + sb.progress / 100f
                cfg.musicSensitivity = v
                BridgeService.hub?.setSensitivity((v * 100).toInt())
            }
        })
        findViewById<SeekBar>(R.id.sequenceSpeed).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                // 0 = slow (5 s per colour), 100 = fast (0.2 s per colour)
                BridgeService.hub?.setSpeed(sb.progress)
            }
        })
        musicSource = findViewById(R.id.musicSource)
        musicSource.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                val name = sourceNames.getOrNull(pos) ?: return
                val hub = BridgeService.hub ?: return
                if (name != hub.musicSourceName()) hub.setMusicSource(name)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        fireSpeed = findViewById(R.id.fireSpeed)
        fireStrength = findViewById(R.id.fireStrength)
        fireBrightness = findViewById(R.id.fireBrightness)
        fun fireListener(apply: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { fireTouched = System.currentTimeMillis() }
            override fun onStopTrackingTouch(sb: SeekBar) { fireTouched = System.currentTimeMillis(); apply(sb.progress) }
        }
        fireSpeed.setOnSeekBarChangeListener(fireListener { BridgeService.hub?.setFire(speed = it) })
        fireStrength.setOnSeekBarChangeListener(fireListener { BridgeService.hub?.setFire(strength = it) })
        fireBrightness.setOnSeekBarChangeListener(fireListener { BridgeService.hub?.setFire(brightness = it) })
        bridgeName = findViewById(R.id.bridgeName)
        bridgeName.setText(cfg.bridgeName)
        kioskUrl = findViewById(R.id.kioskUrl)
        kioskPin = findViewById(R.id.kioskPin)
        kioskEnabled = findViewById(R.id.kioskEnabled)
        kioskUrl.setText(cfg.kioskUrl)
        kioskPin.setText(cfg.kioskPin)
        kioskEnabled.isChecked = cfg.kioskEnabled
        findViewById<Button>(R.id.btnKiosk).setOnClickListener {
            saveFields()
            startActivity(Intent(this, KioskActivity::class.java))
            finish()
        }
        findViewById<Button>(R.id.btnHome).setOnClickListener {
            saveFields()
            try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (_: Exception) {
                // fallback: press home, Android asks which launcher to use
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            }
        }
        val debug = findViewById<CheckBox>(R.id.debugLog)
        debug.isChecked = cfg.debugLog
        debug.setOnCheckedChangeListener { _, checked ->
            cfg.debugLog = checked
            BridgeLog.verbose = checked
        }
        btnInstallUpdate.setOnClickListener { installUpdate() }

        if (!hasLocation()) requestPerms()
    }

    override fun onResume() {
        super.onResume()
        if (autostartOnly) return
        ui.post(refresher)
        // check on every app start / return to the app (independent of the bridge service)
        if (System.currentTimeMillis() - Updater.lastCheck > 2 * 60 * 1000L) checkUpdate(manual = false)
    }

    override fun onPause() {
        super.onPause()
        if (autostartOnly) return
        ui.removeCallbacks(refresher)
    }

    private fun refresh() {
        val s = if (BridgeService.running) "läuft – ${BridgeService.meshState}" else "gestoppt"
        setIfChanged(status, "Status: $s")
        setIfChanged(logView, BridgeLog.snapshot().takeLast(120).reversed().joinToString("\n"))
        refreshUpdate()
        refreshLamps()
    }

    // ---------------------------------------------------------------- lamp controls

    private fun buildLampPanels() {
        lampsBox.removeAllViews()
        lampPanels.clear()
        val lights = cfg.devices.filter { it.isLight }
        if (lights.isEmpty()) {
            lampsBox.addView(TextView(this).apply { text = "Noch keine Lampen importiert (siehe unten)." })
            return
        }
        for (d in lights) {
            val panel = LampPanel(this, d)
            lampPanels[d.meshId] = panel
            lampsBox.addView(panel)
        }
        val box = findViewById<LinearLayout>(R.id.musicLamps)
        box.removeAllViews()
        musicChecks.clear()
        for (d in lights.filter { it.hasColor }) {
            val cb = CheckBox(this)
            cb.text = d.name.trim()
            cb.isChecked = true
            cb.setOnCheckedChangeListener(musicCheckListener(d.meshId))
            musicChecks[d.meshId] = cb
            box.addView(cb)
        }
    }

    private fun musicCheckListener(id: Int) = android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
        BridgeService.hub?.setMusicSelected(id, checked)
    }

    private fun refreshLamps() {
        val hub = BridgeService.hub
        for ((id, panel) in lampPanels) panel.update(hub?.uiState(id), hub?.ownerName(id))
        if (hub != null) {
            for ((id, cb) in musicChecks) {
                val sel = hub.musicSelected(id)
                if (cb.isChecked != sel) {
                    // reflect changes made in Home Assistant / on another tablet without re-triggering
                    cb.setOnCheckedChangeListener(null)
                    cb.isChecked = sel
                    cb.setOnCheckedChangeListener(musicCheckListener(id))
                }
            }
            val names = hub.bridgeNames()
            if (names != sourceNames) {
                sourceNames = names
                musicSource.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            }
            val idx = names.indexOf(hub.musicSourceName())
            if (idx >= 0 && musicSource.selectedItemPosition != idx) musicSource.setSelection(idx, false)
        }
        // reflect campfire settings (also when changed in Home Assistant)
        val lc = BridgeService.controller
        if (lc != null && System.currentTimeMillis() - fireTouched > 3000) {
            if (fireSpeed.progress != lc.fireSpeed) fireSpeed.progress = lc.fireSpeed
            if (fireStrength.progress != lc.fireStrength) fireStrength.progress = lc.fireStrength
            if (fireBrightness.progress != lc.fireBrightness) fireBrightness.progress = lc.fireBrightness
        }
        val label = if (hub?.musicActive == true) "Musikmodus stoppen" else "Musikmodus starten"
        if (btnMusic.text.toString() != label) btnMusic.text = label
    }

    private fun toggleMusic() {
        val hub = BridgeService.hub
        if (hub == null) {
            Toast.makeText(this, "Bitte zuerst die Bridge starten", Toast.LENGTH_LONG).show(); return
        }
        if (hub.musicActive) { hub.setMusicGlobal(false); return }
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        hub.setMusicGlobal(true)
    }

    /** Only touch the view when the text really changed, so the ScrollView does not jump. */
    private fun setIfChanged(v: TextView, text: String) {
        if (v.text.toString() != text) v.text = text
    }

    // ---------------------------------------------------------------- self update

    private fun refreshUpdate() {
        val cur = Updater.currentVersionCode(this)
        val latest = Updater.latest
        val available = latest != null && latest.versionCode > cur
        val base = "Installiert: ${Updater.currentVersionName(this)} (Build $cur)"
        val line2 = when {
            Updater.status.isNotEmpty() -> Updater.status
            updateMsg.isNotEmpty() -> updateMsg
            available -> "Neue Version verfügbar: ${latest!!.versionName} (Build ${latest.versionCode})"
            latest != null -> "Aktuell – keine neuere Version auf GitHub"
            else -> ""
        }
        setIfChanged(updateStatus, if (line2.isEmpty()) base else "$base\n$line2")
        btnInstallUpdate.isEnabled = available && !updateBusy
    }

    private fun checkUpdate(manual: Boolean) {
        if (updateBusy) return
        updateBusy = true
        Updater.status = ""
        updateMsg = "Suche nach Updates …"
        Thread {
            try {
                val info = Updater.check(this)
                updateMsg = ""
                if (info != null) {
                    BridgeLog.i("Update verfügbar: ${info.versionName} (Build ${info.versionCode})")
                    ui.post { promptUpdate(info) }
                } else if (manual) ui.post { Toast.makeText(this, "Keine neue Version", Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                updateMsg = "Update-Prüfung fehlgeschlagen: ${t.message}"
            } finally {
                updateBusy = false
            }
        }.start()
    }

    /** Ask once per version and app session whether to install right away. */
    private fun promptUpdate(info: Updater.Info) {
        if (isFinishing || promptedFor == info.versionCode) return
        promptedFor = info.versionCode
        val notes = if (info.notes.isNotBlank()) "\n\nÄnderung: ${info.notes}" else ""
        AlertDialog.Builder(this)
            .setTitle("Update verfügbar")
            .setMessage("Version ${info.versionName} (Build ${info.versionCode}) ist verfügbar.\n" +
                "Installiert: ${Updater.currentVersionName(this)}$notes")
            .setPositiveButton("Jetzt installieren") { _, _ -> installUpdate() }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun installUpdate() {
        if (!Updater.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("Installation erlauben")
                .setMessage("Bitte im nächsten Bildschirm „Aus dieser Quelle zulassen“ für EGLO Bridge einschalten und danach erneut auf „Update installieren“ tippen.")
                .setPositiveButton("Einstellungen öffnen") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Abbrechen", null)
                .show()
            return
        }
        updateBusy = true
        Updater.status = ""
        Thread {
            try {
                Updater.downloadAndInstall(this) { msg -> updateMsg = msg }
                updateMsg = "Bitte Installation bestätigen"
            } catch (t: Throwable) {
                BridgeLog.e("Update-Download fehlgeschlagen", t)
                updateMsg = "Download fehlgeschlagen: ${t.message}"
            } finally {
                updateBusy = false
            }
        }.start()
    }

    private fun showDevices() {
        val d = cfg.devices
        devicesView.text = if (d.isEmpty()) "Keine Lampen importiert" else
            "Mesh: ${cfg.meshName}\n" + d.joinToString("\n") {
                "• ${it.name}  [ID ${it.meshId}]  ${it.mac}\n   ${it.model} – ${it.type}"
            }
    }

    private fun saveFields() {
        cfg.awoxUser = awoxUser.text.toString().trim()
        cfg.awoxPassword = awoxPassword.text.toString()
        cfg.mqttHost = mqttHost.text.toString().trim()
        cfg.mqttPort = mqttPort.text.toString().toIntOrNull() ?: 1883
        cfg.mqttUser = mqttUser.text.toString().trim()
        cfg.mqttPassword = mqttPassword.text.toString()
        val n = bridgeName.text.toString().trim()
        if (n.isNotEmpty()) cfg.bridgeName = n
        kioskUrl.text.toString().trim().takeIf { it.isNotEmpty() }?.let { cfg.kioskUrl = it }
        kioskPin.text.toString().trim().takeIf { it.isNotEmpty() }?.let { cfg.kioskPin = it }
        cfg.kioskEnabled = kioskEnabled.isChecked
    }

    private fun importFromCloud() {
        saveFields()
        if (cfg.awoxUser.isEmpty() || cfg.awoxPassword.isEmpty()) {
            Toast.makeText(this, "Bitte AwoX-E-Mail und Passwort eingeben", Toast.LENGTH_LONG).show()
            return
        }
        devicesView.text = "Importiere …"
        val user = cfg.awoxUser
        val pass = cfg.awoxPassword
        Thread {
            try {
                val r = AwoxCloud(user, pass).load()
                cfg.meshName = r.meshName
                cfg.meshPassword = r.meshPassword
                cfg.devices = r.devices
                BridgeLog.i("Import: ${r.devices.size} Geräte, Mesh '${r.meshName}'")
                ui.post {
                    showDevices()
                    buildLampPanels()
                    Toast.makeText(this, "${r.devices.size} Lampen importiert", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                BridgeLog.e("Import fehlgeschlagen", t)
                ui.post {
                    showDevices()
                    AlertDialog.Builder(this).setTitle("Import fehlgeschlagen")
                        .setMessage(t.message ?: t.toString()).setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun saveAndStart() {
        saveFields()
        if (!cfg.isComplete) {
            Toast.makeText(this, "Bitte zuerst Lampen importieren und MQTT-Host eintragen", Toast.LENGTH_LONG).show()
            return
        }
        cfg.enabled = true
        if (BridgeService.running) BridgeService.stop(this)
        ui.postDelayed({
            BridgeService.start(this)
            if (cfg.kioskEnabled) { startActivity(Intent(this, KioskActivity::class.java)); finish() }
        }, 800)
    }

    private fun hasLocation(): Boolean = Build.VERSION.SDK_INT < 23 ||
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        if (Build.VERSION.SDK_INT < 23) return
        if (!hasLocation()) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
            )
        } else if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            AlertDialog.Builder(this)
                .setTitle("Standort „Immer zulassen“")
                .setMessage("Damit die Bridge im Hintergrund nach Lampen suchen kann, bitte in den App-Einstellungen unter Berechtigungen → Standort „Immer zulassen“ wählen.")
                .setPositiveButton("Einstellungen öffnen") { _, _ -> openAppSettings() }
                .setNegativeButton("Später", null)
                .show()
        } else {
            Toast.makeText(this, "Standort-Berechtigung ist vorhanden", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && hasLocation()) requestPerms()
        if (requestCode == 2 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Mikrofon erlaubt", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Akku-Optimierung ist bereits aus", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
