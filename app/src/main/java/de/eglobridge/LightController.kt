package de.eglobridge

import android.os.Handler
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * Central light logic shared by Home Assistant (MQTT) and the in-app controls:
 * keeps the last known state per lamp, translates JSON commands into mesh
 * commands, runs effects and the music mode.
 */
class LightController(
    private val handler: Handler,
    private val mesh: MeshManager,
    val devices: List<MeshDevice>
) {
    companion object {
        const val MIN_K = 2700
        const val MAX_K = 6500

        const val EFFECT_NONE = "Kein Effekt"
        const val EFFECT_CANDLE = "Kerze"
        const val EFFECT_MUSIC = "Musik"
        /** AwoX colour sequences (preset 0..6). */
        val SEQUENCES = listOf(
            "Farbwechsel", "Sequenz 2", "Sequenz 3", "Sequenz 4", "Sequenz 5", "Sequenz 6", "Sequenz 7"
        )
        val EFFECTS = listOf(EFFECT_NONE) + SEQUENCES + listOf(EFFECT_CANDLE, EFFECT_MUSIC)

        /** Same rounding/clamping as the HA AwoX integration. */
        fun convert(value: Int, minFrom: Int, maxFrom: Int, minTo: Int, maxTo: Int): Int {
            val normalized = (value - minFrom).toDouble() / (maxFrom - minFrom)
            val v = (normalized * (maxTo - minTo) + minTo).roundToInt()
            return v.coerceIn(minTo, maxTo)
        }

        fun deviceTempToKelvin(t: Int) = convert(0x7f - t.coerceIn(0, 0x7f), 0, 0x7f, MIN_K, MAX_K)
        fun kelvinToDeviceTemp(k: Int) = 0x7f - convert(k, MIN_K, MAX_K, 0, 0x7f)
    }

    class LightState {
        var available = false
        var on = false
        var colorMode = false
        var whiteBrightness = 0x7f
        var temperature = 0x40
        var colorBrightness = 0x64
        var r = 255; var g = 255; var b = 255
        var effect = EFFECT_NONE
        var known = false

        /** Brightness on the HA scale 0..255 for the active mode. */
        val brightness255: Int get() = if (colorMode) convert(colorBrightness, 0x0a, 0x64, 0, 255)
            else convert(whiteBrightness, 1, 0x7f, 0, 255)
        val kelvin: Int get() = deviceTempToKelvin(temperature)
    }

    /** Called whenever music mode / tempo / sensitivity changed (for MQTT state). */
    val settingsListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Lamps that take part when the music mode is started for "all" (selection). */
    private val musicSelection = HashSet<Int>()

    fun initMusicSelection(sel: Set<Int>?) {
        musicSelection.clear()
        musicSelection.addAll(sel ?: lights.map { it.meshId })
    }

    fun musicSelected(id: Int) = synchronized(musicSelection) { musicSelection.contains(id) }
    fun musicSelectionSnapshot(): Set<Int> = synchronized(musicSelection) { musicSelection.toSet() }

    /** Select / deselect a lamp for the music mode; applies immediately while music is running. */
    fun setMusicSelected(id: Int, selected: Boolean) {
        handler.post {
            val changed = synchronized(musicSelection) {
                if (selected) musicSelection.add(id) else musicSelection.remove(id)
            }
            if (!changed) return@post
            BridgeLog.i("Musikmodus: ${mesh.deviceName(id)} ${if (selected) "ausgewählt" else "abgewählt"}")
            if (musicTargets.isNotEmpty()) {
                if (selected && isAvailable(id)) {
                    command(id, JSONObject().put("state", "ON").put("effect", EFFECT_MUSIC))
                } else if (!selected && musicTargets.contains(id)) {
                    stopMusicFor(id)
                    // back to the previous static light
                    command(id, JSONObject().put("effect", EFFECT_NONE))
                }
            }
            settingsListeners.forEach { it() }
        }
    }

    /** Colour-sequence tempo 0 (slow) .. 100 (fast). */
    @Volatile var sequenceSpeed = 50
        private set

    /** Called (on the bridge thread) whenever a lamp's state changed. */
    val stateListeners = CopyOnWriteArrayList<(Int, LightState) -> Unit>()
    val availabilityListeners = CopyOnWriteArrayList<(Int, Boolean) -> Unit>()

    private val states = HashMap<Int, LightState>()
    private val lastPreset = HashMap<Int, Int>()

    // music mode
    private val musicTargets = HashSet<Int>()
    private var music: MusicMode? = null
    @Volatile var musicSensitivity = 1.0f
        set(v) {
            field = v.coerceIn(0.5f, 2.0f)
            music?.sensitivity = field
            settingsListeners.forEach { it() }
        }
    private var lastMusicBrightness = -1
    private var lastMusicColorAt = 0L

    val lights get() = devices.filter { it.isLight }
    fun device(id: Int) = devices.firstOrNull { it.meshId == id }

    /** Thread-safe snapshot for the UI. */
    fun snapshot(): Map<Int, LightState> = synchronized(states) {
        states.mapValues { (_, s) ->
            LightState().also {
                it.available = s.available; it.on = s.on; it.colorMode = s.colorMode
                it.whiteBrightness = s.whiteBrightness; it.temperature = s.temperature
                it.colorBrightness = s.colorBrightness; it.r = s.r; it.g = s.g; it.b = s.b
                it.effect = s.effect; it.known = s.known
            }
        }
    }

    fun isAvailable(id: Int) = synchronized(states) { states[id]?.available == true }
    val musicActive get() = musicTargets.isNotEmpty()

    private fun st(id: Int) = synchronized(states) { states.getOrPut(id) { LightState() } }

    private fun notifyState(id: Int) {
        val s = st(id)
        stateListeners.forEach { it(id, s) }
    }

    // ------------------------------------------------------------------ mesh -> state

    fun onStatus(s: LightStatus) {
        val st = st(s.meshId)
        synchronized(states) {
            st.on = s.on
            st.colorMode = s.colorMode
            st.whiteBrightness = s.whiteBrightness
            st.temperature = s.temperature
            st.colorBrightness = s.colorBrightness
            if (!musicTargets.contains(s.meshId)) {
                st.r = s.r; st.g = s.g; st.b = s.b
                st.effect = when {
                    s.candleMode -> EFFECT_CANDLE
                    s.sequenceMode -> SEQUENCES[(lastPreset[s.meshId] ?: 0).coerceIn(0, SEQUENCES.size - 1)]
                    else -> EFFECT_NONE
                }
            }
            st.known = true
        }
        setAvailable(s.meshId, true)
        notifyState(s.meshId)
    }

    fun setAvailable(id: Int, available: Boolean) {
        val st = st(id)
        if (st.available == available) return
        st.available = available
        availabilityListeners.forEach { it(id, available) }
        notifyState(id)
    }

    fun markAllOffline() {
        for (d in devices) setAvailable(d.meshId, false)
    }

    // ------------------------------------------------------------------ commands

    /**
     * Apply a Home Assistant style JSON command:
     * {"state":"ON","brightness":0..255,"color":{"r","g","b"},"color_temp":kelvin,"effect":"…"}
     */
    fun command(id: Int, root: JSONObject) {
        handler.post { applyCommand(id, root) }
    }

    private fun applyCommand(id: Int, root: JSONObject) {
        BridgeLog.i("Befehl für ${mesh.deviceName(id)}: $root")
        val st = st(id)
        var stateSet = false

        // any explicit colour / temperature / effect change ends music mode for this lamp
        val effect = root.optString("effect", "")
        if (musicTargets.contains(id) && (effect.isNotEmpty() && effect != EFFECT_MUSIC ||
                root.has("color") || root.has("color_temp") || root.optString("state") == "OFF")) {
            stopMusicFor(id)
        }

        root.optJSONObject("color")?.let { c ->
            val r = c.optInt("r", 255); val g = c.optInt("g", 255); val b = c.optInt("b", 255)
            mesh.setColor(id, r, g, b)
            st.r = r; st.g = g; st.b = b
            st.colorMode = true; st.on = true; st.effect = EFFECT_NONE; stateSet = true
        }

        if (root.has("brightness") && !root.has("color_temp") && (root.has("color") || st.colorMode)) {
            val v = convert(root.getInt("brightness"), 0, 255, 0x0a, 0x64)
            mesh.setColorBrightness(id, v)
            st.colorBrightness = v; st.on = true; stateSet = true
        } else if (root.has("brightness")) {
            val v = convert(root.getInt("brightness"), 0, 255, 1, 0x7f)
            mesh.setWhiteBrightness(id, v)
            st.whiteBrightness = v; st.on = true; stateSet = true
        }

        if (root.has("color_temp")) {
            val v = kelvinToDeviceTemp(root.getInt("color_temp"))
            mesh.setWhiteTemperature(id, v)
            st.temperature = v; st.colorMode = false; st.on = true; st.effect = EFFECT_NONE; stateSet = true
        }

        if (effect.isNotEmpty()) {
            stateSet = true
            st.on = true
            applyEffect(id, st, effect)
        }

        when (root.optString("state").uppercase()) {
            "ON" -> {
                if (!stateSet) mesh.setPower(id, true)
                st.on = true
            }
            "OFF" -> {
                mesh.setPower(id, false)
                st.on = false
            }
        }
        st.known = true
        notifyState(id)
    }

    private fun applyEffect(id: Int, st: LightState, effect: String) {
        val seq = SEQUENCES.indexOf(effect)
        when {
            seq >= 0 -> {
                lastPreset[id] = seq
                mesh.setPreset(id, seq)
                st.effect = effect; st.colorMode = true
            }
            effect == EFFECT_CANDLE -> {
                mesh.setCandle(id)
                st.effect = effect
            }
            effect == EFFECT_MUSIC -> {
                startMusicFor(id)
                st.effect = effect; st.colorMode = true
            }
            else -> {
                // "Kein Effekt": go back to the last static colour / white
                st.effect = EFFECT_NONE
                if (st.colorMode) mesh.setColor(id, st.r, st.g, st.b, coalesce = false)
                else mesh.setWhiteTemperature(id, st.temperature)
            }
        }
    }

    /** Speed of the colour sequences: [colorMs] how long a colour stays, [fadeMs] cross-fade. */
    fun setSequenceTiming(id: Int, colorMs: Int, fadeMs: Int) {
        mesh.setSequenceColorDuration(id, colorMs)
        mesh.setSequenceFadeDuration(id, fadeMs)
    }

    /** Tempo 0 (slow, 5 s per colour) .. 100 (fast, 0.2 s per colour) for all lamps. */
    fun setSequenceSpeed(speed: Int) {
        sequenceSpeed = speed.coerceIn(0, 100)
        val colorMs = (5000 - sequenceSpeed * 48).coerceAtLeast(200)
        val fadeMs = colorMs / 2
        lights.forEach { setSequenceTiming(it.meshId, colorMs, fadeMs) }
        BridgeLog.i("Effekt-Tempo $sequenceSpeed (${colorMs} ms pro Farbe)")
        settingsListeners.forEach { it() }
    }

    /** Music mode on all selected and reachable lamps. */
    fun startMusicAll(): Boolean {
        val targets = lights.filter { musicSelected(it.meshId) && isAvailable(it.meshId) }
        if (targets.isEmpty()) {
            BridgeLog.w("Musikmodus: keine ausgewählte Lampe erreichbar")
            return false
        }
        targets.forEach { command(it.meshId, JSONObject().put("state", "ON").put("effect", EFFECT_MUSIC)) }
        return true
    }

    // ------------------------------------------------------------------ music

    fun startMusicFor(id: Int) {
        handler.post {
            musicTargets.add(id)
            st(id).apply { effect = EFFECT_MUSIC; colorMode = true; on = true }
            if (music == null) {
                val m = MusicMode { r, g, b, level, beat -> onMusicFrame(r, g, b, level, beat) }
                m.sensitivity = musicSensitivity
                if (!m.start()) {
                    musicTargets.clear()
                    st(id).effect = EFFECT_NONE
                    notifyState(id)
                    return@post
                }
                music = m
                lastMusicBrightness = -1
                settingsListeners.forEach { it() }
            }
            notifyState(id)
        }
    }

    fun stopMusicFor(id: Int) {
        handler.post {
            if (!musicTargets.remove(id)) return@post
            st(id).effect = EFFECT_NONE
            if (musicTargets.isEmpty()) {
                music?.stop()
                music = null
                settingsListeners.forEach { it() }
            }
            notifyState(id)
        }
    }

    fun stopAllMusic() {
        handler.post {
            val ids = musicTargets.toList()
            musicTargets.clear()
            music?.stop()
            music = null
            ids.forEach { st(it).effect = EFFECT_NONE; notifyState(it) }
            settingsListeners.forEach { it() }
        }
    }

    /** Called from the audio thread. */
    private fun onMusicFrame(r: Int, g: Int, b: Int, level: Float, beat: Boolean) {
        val now = System.currentTimeMillis()
        // about 4 colour updates per second (BLE mesh can't do much more), immediately on beats
        if (!beat && now - lastMusicColorAt < 240) return
        lastMusicColorAt = now
        handler.post {
            if (musicTargets.isEmpty()) return@post
            val targets = musicTargets.toList()
            val all = lights.all { musicTargets.contains(it.meshId) || !isAvailable(it.meshId) }
            val dests = if (all && targets.size > 1) listOf(MeshManager.BROADCAST) else targets
            val bright = (0x0a + level * (0x64 - 0x0a)).toInt().coerceIn(0x0a, 0x64)
            for (d in dests) {
                mesh.setColor(d, r, g, b, coalesce = true)
                if (kotlin.math.abs(bright - lastMusicBrightness) >= 8 || beat) {
                    mesh.setColorBrightness(d, bright, coalesce = true)
                }
            }
            if (kotlin.math.abs(bright - lastMusicBrightness) >= 8 || beat) lastMusicBrightness = bright
            for (t in targets) {
                st(t).apply { this.r = r; this.g = g; this.b = b; colorBrightness = bright }
            }
        }
    }

    fun shutdown() {
        music?.stop()
        music = null
        musicTargets.clear()
    }
}
