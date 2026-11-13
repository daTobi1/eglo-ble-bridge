package de.eglobridge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Fullscreen Home Assistant dashboard. Registered as a launcher (HOME) so the tablet
 * always comes back here. No lock-task pinning on purpose: the system installer
 * window for app updates must be able to appear on top.
 * Exit: the gear button bottom-left (or 5 taps top-left), then the PIN.
 */
class KioskActivity : Activity(), KioskControl.Ui {

    private lateinit var cfg: Config
    private lateinit var web: WebView
    private lateinit var overlay: View
    private lateinit var errorView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var lastTouch = SystemClock.elapsedRealtime()
    private var cornerTaps = 0
    private var firstCornerTap = 0L
    private var updateDialog: AlertDialog? = null
    private var shownUpdate = -1

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        if (!cfg.kioskEnabled) {
            // kiosk was switched off but we are still registered as launcher: hand over to the normal one
            openOtherLauncher()
            finish()
            return
        }
        // the bridge runs in the background; starting it from here (foreground) also allows the microphone
        if (cfg.enabled && cfg.isComplete && !BridgeService.running) BridgeService.start(this)

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        errorView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#111318")); visibility = View.GONE
        }
        root.addView(errorView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // black overlay used as "screen off" (touch wakes it up)
        overlay = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        // small exit/settings button (PIN protected)
        val exitBtn = TextView(this).apply {
            text = "⚙"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#99111318"))
            }
            alpha = 0.55f
            setOnClickListener { askPin() }
        }
        root.addView(exitBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(10); bottomMargin = dp(10)
        })

        buildVoiceViews(root)

        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val why = if (android.os.Build.VERSION.SDK_INT >= 23) "${error.description} (${error.errorCode})" else ""
                    showError("Dashboard nicht erreichbar: ${request.url}\n$why\nNeuer Versuch in 15 s")
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // local Home Assistant with a self-signed certificate
                handler.proceed()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (errorView.visibility == View.VISIBLE && !url.startsWith("about:")) errorView.visibility = View.GONE
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // e.g. Assist / camera cards inside the dashboard
                request.grant(request.resources)
            }
        }
        web.loadUrl(cfg.kioskUrl)
        ui.postDelayed(timeoutCheck, 30000)
        // if the app was restarted while the screen was "off", put the display timeout back
        restoreSystemTimeout()
    }

    private fun showError(msg: String) {
        errorView.text = msg
        errorView.visibility = View.VISIBLE
        ui.removeCallbacks(retry)
        ui.postDelayed(retry, 15000)
    }

    private val retry = Runnable { web.loadUrl(cfg.kioskUrl) }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        KioskControl.ui = this
        KioskControl.visible = true
        setBrightness(cfg.kioskBrightness)
        Updater.latest?.let { if (it.versionCode > Updater.currentVersionCode(this)) showUpdate(it) }
        if (KioskControl.wakeRequested) { KioskControl.wakeRequested = false; setScreen(true) }
        showVoice(KioskControl.voice)
        BridgeService.refreshFromForeground(this)
    }

    override fun onPause() {
        super.onPause()
        if (KioskControl.ui === this) KioskControl.ui = null
        KioskControl.visible = false
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack()
        // never leave the kiosk with the back button
    }

    // ------------------------------------------------------------------ touch: wake, timeout, hidden exit

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = SystemClock.elapsedRealtime()
        if (!KioskControl.screenOn) {
            if (ev.action == MotionEvent.ACTION_DOWN) setScreen(true)
            return true   // the waking touch does not reach the dashboard
        }
        if (ev.action == MotionEvent.ACTION_DOWN && ev.x < dp(70) && ev.y < dp(70)) {
            val now = SystemClock.elapsedRealtime()
            if (now - firstCornerTap > 3000) { firstCornerTap = now; cornerTaps = 0 }
            cornerTaps++
            if (cornerTaps >= 5) { cornerTaps = 0; askPin() }
        }
        return super.dispatchTouchEvent(ev)
    }

    private val timeoutCheck = object : Runnable {
        override fun run() {
            val minutes = cfg.kioskScreenTimeout
            if (minutes > 0 && KioskControl.screenOn &&
                SystemClock.elapsedRealtime() - lastTouch > minutes * 60_000L
            ) {
                setScreen(false)
            }
            ui.postDelayed(this, 30000)
        }
    }

    private fun askPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Kiosk verlassen")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == cfg.kioskPin) showKioskMenu()
                else Toast.makeText(this, "Falsche PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showKioskMenu() {
        AlertDialog.Builder(this)
            .setTitle("Kiosk")
            .setItems(arrayOf("Kiosk-Modus beenden", "Bridge-Einstellungen öffnen", "Dashboard neu laden", "Abbrechen")) { _, which ->
                when (which) {
                    0 -> exitKiosk()
                    1 -> startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FROM_KIOSK, true))
                    2 -> reload()
                }
            }
            .show()
    }

    /** Leaves kiosk mode for good (until "Kiosk starten" is pressed again in the app). The bridge keeps running. */
    private fun exitKiosk() {
        cfg.kioskEnabled = false
        setScreen(true)
        Toast.makeText(this, "Kiosk-Modus beendet – in der App über „Kiosk starten“ wieder aktivieren", Toast.LENGTH_LONG).show()
        openOtherLauncher()
        finish()
    }

    private fun openOtherLauncher() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = packageManager.queryIntentActivities(home, 0)
            .firstOrNull { it.activityInfo.packageName != packageName }
        val target = if (other != null) {
            Intent(home).setClassName(other.activityInfo.packageName, other.activityInfo.name)
        } else {
            Intent(this, MainActivity::class.java)
        }
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ------------------------------------------------------------------ voice assistant overlay

    private lateinit var voiceCard: android.widget.LinearLayout
    private lateinit var voiceTitle: TextView
    private lateinit var voiceHeard: TextView
    private lateinit var voiceAnswer: TextView
    private lateinit var timerBox: android.widget.LinearLayout
    private var voiceView: VoiceView? = null

    private fun pill(color: String) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(Color.parseColor(color))
    }

    private fun buildVoiceViews(root: FrameLayout) {
        voiceCard = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = pill("#E6111318")
            setPadding(dp(22), dp(14), dp(22), dp(16))
            visibility = View.GONE
            elevation = dp(8).toFloat()
            setOnClickListener { VoiceSatellite.current?.stopAlarm() }
        }
        voiceTitle = TextView(this).apply { setTextColor(Color.parseColor("#8AB4F8")); textSize = 16f }
        voiceHeard = TextView(this).apply {
            setTextColor(Color.parseColor("#BBBBBB")); textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.ITALIC)
        }
        voiceAnswer = TextView(this).apply { setTextColor(Color.WHITE); textSize = 21f; maxLines = 8 }
        voiceCard.addView(voiceTitle)
        voiceCard.addView(voiceHeard)
        voiceCard.addView(voiceAnswer)
        root.addView(voiceCard, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(28); leftMargin = dp(70); rightMargin = dp(70) })

        timerBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
        }
        root.addView(timerBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
        ).apply { topMargin = dp(16); rightMargin = dp(16) })
    }

    override fun showVoice(v: VoiceView?) {
        if (!::voiceCard.isInitialized) return
        voiceView = v
        if (v == null) {
            voiceCard.visibility = View.GONE
            timerBox.visibility = View.GONE
            ui.removeCallbacks(timerTick)
            return
        }
        val title = when (v.phase) {
            "listening" -> "🎙  Ich höre zu …"
            "thinking" -> "…  Einen Moment"
            "responding" -> "🔊"
            "announcing" -> "📢  Durchsage"
            "error" -> "⚠  Das hat nicht geklappt"
            else -> ""
        }
        val show = v.phase != "idle" || v.answer.isNotEmpty() || v.heard.isNotEmpty()
        voiceTitle.text = title
        voiceTitle.visibility = if (title.isEmpty()) View.GONE else View.VISIBLE
        voiceHeard.text = if (v.heard.isNotEmpty()) "„${v.heard}“" else ""
        voiceHeard.visibility = if (v.heard.isEmpty()) View.GONE else View.VISIBLE
        voiceAnswer.text = v.answer
        voiceAnswer.visibility = if (v.answer.isEmpty()) View.GONE else View.VISIBLE
        voiceCard.visibility = if (show) View.VISIBLE else View.GONE
        renderTimers()
    }

    private fun fmt(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun renderTimers() {
        val v = voiceView
        timerBox.removeAllViews()
        ui.removeCallbacks(timerTick)
        if (v == null || v.timers.isEmpty()) { timerBox.visibility = View.GONE; return }
        val now = SystemClock.elapsedRealtime()
        for (t in v.timers) {
            val left = if (t.active) ((t.endsAt - now + 999) / 1000).toInt() else t.secondsLeft
            val name = t.name.ifEmpty { "Timer" }
            val tv = TextView(this).apply {
                textSize = 20f
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                text = when {
                    t.finished -> "⏰  $name – abgelaufen (tippen)"
                    !t.active -> "⏸  $name  ${fmt(left)}"
                    else -> "⏱  $name  ${fmt(left)}"
                }
                background = pill(if (t.finished) "#E6C62828" else "#E6111318")
                setOnClickListener { VoiceSatellite.current?.stopAlarm() }
            }
            timerBox.addView(tv, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
        timerBox.visibility = View.VISIBLE
        if (v.timers.any { it.active }) ui.postDelayed(timerTick, 1000)
    }

    private val timerTick = Runnable { renderTimers() }

    // ------------------------------------------------------------------ KioskControl.Ui (main thread)

    override fun exit() {
        if (!isFinishing) exitKiosk()
    }

    override fun reload() {
        errorView.visibility = View.GONE
        web.reload()
    }

    override fun loadUrl(url: String) {
        errorView.visibility = View.GONE
        web.loadUrl(url)
    }

    @Suppress("DEPRECATION")
    override fun setScreen(on: Boolean) {
        if (on) {
            // wake the display if the system already switched it off
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            try {
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "eglobridge:wake"
                )
                wl.acquire(3000)
            } catch (_: Throwable) { }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            overlay.visibility = View.GONE
            // must be set before setBrightness(), which ignores calls while the screen is "off" –
            // otherwise the window stays at the 1 % used for "screen off"
            KioskControl.screenOn = true
            setBrightness(cfg.kioskBrightness)
            lastTouch = SystemClock.elapsedRealtime()
            restoreSystemTimeout()
        } else {
            overlay.visibility = View.VISIBLE
            val lp = window.attributes
            lp.screenBrightness = 0.01f
            window.attributes = lp
            // really switch the display off: with "modify system settings" we shorten Android's
            // display timeout for a moment, otherwise Android uses its own timeout
            setShortSystemTimeout()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        KioskControl.screenOn = on
    }

    private fun canWriteSettings() = Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this)

    private fun setShortSystemTimeout() {
        if (!canWriteSettings()) return
        try {
            val current = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60000)
            if (current > 5000) cfg.savedScreenTimeout = current
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 5000)
        } catch (t: Throwable) {
            BridgeLog.w("Display-Timeout konnte nicht gesetzt werden: ${t.message}")
        }
    }

    private fun restoreSystemTimeout() {
        if (!canWriteSettings()) return
        try {
            val current = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60000)
            if (current <= 5000) {
                val back = cfg.savedScreenTimeout.takeIf { it > 5000 } ?: 60000
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, back)
            }
        } catch (_: Throwable) { }
    }

    override fun setBrightness(percent: Int) {
        if (!KioskControl.screenOn) return
        val lp = window.attributes
        lp.screenBrightness = (percent.coerceIn(1, 100) / 100f)
        window.attributes = lp
    }

    override fun showUpdate(info: Updater.Info) {
        if (shownUpdate == info.versionCode && updateDialog?.isShowing == true) return
        shownUpdate = info.versionCode
        if (!KioskControl.screenOn) setScreen(true)
        updateDialog?.dismiss()
        val notes = if (info.notes.isNotBlank()) "\n\nÄnderung: ${info.notes}" else ""
        updateDialog = AlertDialog.Builder(this)
            .setTitle("Update für EGLO Bridge")
            .setMessage("Version ${info.versionName} ist verfügbar (installiert: ${Updater.currentVersionName(this)}).$notes")
            .setPositiveButton("Jetzt installieren") { _, _ ->
                Toast.makeText(this, "Update wird geladen …", Toast.LENGTH_LONG).show()
                BridgeService.hub?.requestInstallUpdate() ?: Thread {
                    try { Updater.downloadAndInstall(this) { } } catch (t: Throwable) {
                        BridgeLog.e("Update-Download fehlgeschlagen", t)
                    }
                }.start()
            }
            .setNegativeButton("Später", null)
            .show()
    }
}
