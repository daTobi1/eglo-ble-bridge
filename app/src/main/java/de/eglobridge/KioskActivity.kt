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
 * Hidden exit: tap the top-left corner 5 times, then enter the PIN.
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
                if (request.isForMainFrame) showError("Dashboard nicht erreichbar – neuer Versuch in 15 s")
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
    }

    override fun onPause() {
        super.onPause()
        if (KioskControl.ui === this) KioskControl.ui = null
        KioskControl.visible = false
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        web.destroy()
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
                if (input.text.toString() == cfg.kioskPin) {
                    startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FROM_KIOSK, true))
                } else Toast.makeText(this, "Falsche PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ------------------------------------------------------------------ KioskControl.Ui (main thread)

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
            setBrightness(cfg.kioskBrightness)
            lastTouch = SystemClock.elapsedRealtime()
        } else {
            overlay.visibility = View.VISIBLE
            val lp = window.attributes
            lp.screenBrightness = 0.01f
            window.attributes = lp
            // let Android switch the backlight off after its own display timeout
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        KioskControl.screenOn = on
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
