package de.eglobridge

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject

/** In-app controls for one lamp, built in code. */
class LampPanel(context: Context, private val device: MeshDevice) : LinearLayout(context) {

    private val title = TextView(context)
    private val info = TextView(context)
    private val power = Switch(context)
    private val brightness = SeekBar(context)
    private val temperature = SeekBar(context)
    private val hue = SeekBar(context)
    private val swatch = View(context)
    private val effect = Spinner(context)
    private var lastUserAction = 0L
    private var updating = false
    private var shownEffectIdx = 0
    private val favButtons = arrayOfNulls<Button>(2)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F5F5F5")); cornerRadius = dp(8).toFloat()
        }
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        layoutParams = lp

        // header: name + availability + power switch
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(context).apply { orientation = VERTICAL }
        title.text = device.name.trim()
        title.textSize = 18f
        info.textSize = 12f
        names.addView(title); names.addView(info)
        header.addView(names, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(power)
        addView(header)

        // favourites: tap = recall, long press = save current setting
        val favRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (n in 1..2) {
            val b = Button(context)
            b.text = "★ Favorit $n"
            b.setOnClickListener {
                touch(); BridgeService.hub?.pressFavorite(device.meshId, n, save = false)
            }
            b.setOnLongClickListener {
                BridgeService.hub?.pressFavorite(device.meshId, n, save = true)
                android.widget.Toast.makeText(context, "Favorit $n gespeichert", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            favButtons[n - 1] = b
            val flp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            flp.setMargins(dp(2), dp(4), dp(2), 0)
            favRow.addView(b, flp)
        }
        addView(favRow)
        addView(TextView(context).apply {
            text = "Tippen = abrufen · lange drücken = aktuelle Einstellung speichern"
            textSize = 11f
        })

        addView(label("Helligkeit"))
        brightness.max = 255
        addView(brightness)

        if (device.hasTemperature) {
            addView(label("Weißton (warm ↔ kalt)"))
            temperature.max = LightController.MAX_K - LightController.MIN_K
            addView(temperature)
        }

        if (device.hasColor) {
            val hueRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            hueRow.addView(label("Farbe"), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            swatch.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(4).toFloat() }
            hueRow.addView(swatch, LayoutParams(dp(40), dp(20)))
            addView(hueRow)
            hue.max = 359
            hue.progressDrawable = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED)
            ).apply { cornerRadius = dp(4).toFloat() }
            addView(hue)

            val presets = LinearLayout(context).apply { orientation = HORIZONTAL }
            val colors = listOf(
                Color.rgb(255, 0, 0), Color.rgb(255, 120, 0), Color.rgb(255, 230, 0), Color.rgb(0, 255, 0),
                Color.rgb(0, 255, 255), Color.rgb(0, 0, 255), Color.rgb(160, 0, 255), Color.rgb(255, 0, 160)
            )
            for (c in colors) {
                val b = Button(context)
                b.background = GradientDrawable().apply { setColor(c); cornerRadius = dp(16).toFloat() }
                b.setOnClickListener { sendColor(Color.red(c), Color.green(c), Color.blue(c)) }
                val blp = LayoutParams(0, dp(34), 1f)
                blp.setMargins(dp(3), dp(6), dp(3), dp(6))
                presets.addView(b, blp)
            }
            addView(presets)

            addView(label("Effekt"))
            effect.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, LightController.EFFECTS)
            addView(effect)
        }

        power.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            touch(); send(JSONObject().put("state", if (checked) "ON" else "OFF"))
        }
        brightness.setOnSeekBarChangeListener(seek { v ->
            send(JSONObject().put("state", "ON").put("brightness", v.coerceAtLeast(1)))
        })
        temperature.setOnSeekBarChangeListener(seek { v ->
            send(JSONObject().put("state", "ON").put("color_temp", LightController.MIN_K + v))
        })
        hue.setOnSeekBarChangeListener(seek { v ->
            val (r, g, b) = hsvToRgb(v.toDouble(), 1.0, 1.0)
            sendColor(r, g, b)
        })
        effect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (updating || pos == shownEffectIdx) return
                shownEffectIdx = pos
                touch(); send(JSONObject().put("state", "ON").put("effect", LightController.EFFECTS[pos]))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text; textSize = 13f; setPadding(0, dp(8), 0, 0)
    }

    private fun touch() { lastUserAction = SystemClock.elapsedRealtime() }

    private fun seek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        private var lastSent = 0L
        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            touch()
            val now = SystemClock.elapsedRealtime()
            if (now - lastSent > 150) { lastSent = now; onChange(progress) }
        }
        override fun onStartTrackingTouch(sb: SeekBar) { touch() }
        override fun onStopTrackingTouch(sb: SeekBar) { touch(); onChange(sb.progress) }
    }

    private fun sendColor(r: Int, g: Int, b: Int) {
        touch()
        (swatch.background as? GradientDrawable)?.setColor(Color.rgb(r, g, b))
        send(JSONObject().put("state", "ON").put("color", JSONObject().put("r", r).put("g", g).put("b", b)))
    }

    private fun send(json: JSONObject) {
        BridgeService.hub?.sendCommand(device.meshId, json)
    }

    /** Refresh from the controller state unless the user just touched a control. */
    fun update(st: LightController.LightState?, owner: String?) {
        val available = st?.available == true
        val via = if (owner != null) " · über $owner" else ""
        info.text = when {
            BridgeService.hub == null -> "Bridge nicht gestartet"
            !available -> "nicht erreichbar"
            st!!.effect != LightController.EFFECT_NONE -> "erreichbar$via · ${st.effect}"
            else -> "erreichbar$via"
        }
        alpha = if (available) 1f else 0.55f
        // show the favourite colours on the buttons
        val hub = BridgeService.hub
        for (n in 1..2) {
            val fav = hub?.favorite(device.meshId, n)
            val b = favButtons[n - 1] ?: continue
            val col = fav?.optJSONObject("color")
            val c = when {
                fav == null -> Color.parseColor("#DDDDDD")
                col != null -> Color.rgb(col.optInt("r"), col.optInt("g"), col.optInt("b"))
                else -> Color.parseColor("#FFE0B2")   // white tone
            }
            (b.background as? GradientDrawable)?.setColor(c) ?: run {
                b.background = GradientDrawable().apply { setColor(c); cornerRadius = dp(6).toFloat() }
            }
        }
        if (st == null || SystemClock.elapsedRealtime() - lastUserAction < 2500) return
        updating = true
        try {
            if (power.isChecked != st.on) power.isChecked = st.on
            brightness.progress = st.brightness255
            if (device.hasTemperature) temperature.progress = st.kelvin - LightController.MIN_K
            if (device.hasColor) {
                (swatch.background as? GradientDrawable)?.setColor(
                    if (st.colorMode) Color.rgb(st.r, st.g, st.b) else Color.WHITE
                )
                if (st.colorMode) {
                    val hsv = FloatArray(3)
                    Color.RGBToHSV(st.r, st.g, st.b, hsv)
                    hue.progress = hsv[0].toInt()
                }
                val idx = LightController.EFFECTS.indexOf(st.effect).coerceAtLeast(0)
                if (effect.selectedItemPosition != idx) {
                    shownEffectIdx = idx
                    effect.setSelection(idx, false)
                }
            }
        } finally {
            updating = false
        }
    }
}
