package de.eglobridge

import android.os.Handler
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * Local light logic of one bridge: keeps the last known state of the lamps this
 * bridge can reach, translates JSON commands into mesh commands, runs effects and
 * applies music frames. Coordination between several bridges (who owns which
 * lamp, global music mode) lives in [MqttBridge].
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

        /** Parse a HA JSON state message (as published by any bridge) into a LightState. */
        fun parseState(o: JSONObject): LightState = LightState().also { st ->
            st.known = true
            st.available = true
            st.on = o.optString("state") == "ON"
            st.colorMode = o.optString("color_mode") == "rgb"
            val br = o.optInt("brightness", 255)
            if (st.colorMode) st.colorBrightness = convert(br, 0, 255, 0x0a, 0x64)
            else st.whiteBrightness = convert(br, 0, 255, 1, 0x7f)
            if (o.has("color_temp")) st.temperature = kelvinToDeviceTemp(o.optInt("color_temp"))
            o.optJSONObject("color")?.let { st.r = it.optInt("r"); st.g = it.optInt("g"); st.b = it.optInt("b") }
            st.effect = o.optString("effect", EFFECT_NONE)
        }
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

        fun copy() = LightState().also {
            it.available = available; it.on = on; it.colorMode = colorMode
            it.whiteBrightness = whiteBrightness; it.temperature = temperature
            it.colorBrightness = colorBrightness; it.r = r; it.g = g; it.b = b
            it.effect = effect; it.known = known
        }
    }

    /** Called (on the bridge thread) whenever a lamp's state changed. */
    val stateListeners = CopyOnWriteArrayList<(Int, LightState) -> Unit>()
    /** Local reachability of a lamp changed. */
    val availabilityListeners = CopyOnWriteArrayList<(Int, Boolean) -> Unit>()

    /** Set by the MQTT layer: a lamp asked for effect "Musik" while music mode is off. */
    @Volatile var onMusicRequested: (() -> Unit)? = null
    /** Set by the MQTT layer: frames from this tablet's microphone. */
    @Volatile var onMicFrame: ((r: Int, g: Int, b: Int, bright: Int, beat: Boolean) -> Unit)? = null

    private val states = HashMap<Int, LightState>()
    private val lastPreset = HashMap<Int, Int>()

    // music mode
    private val musicTargets = HashSet<Int>()
    private var mic: MusicMode? = null
    @Volatile var musicSensitivity = 1.0f
        set(v) { field = v.coerceIn(0.5f, 2.0f); mic?.sensitivity = field }
    @Volatile var musicGlobalOn = false
        private set
    private var lastMusicBrightness = -1
    private var lastMicFrameAt = 0L

    val lights get() = devices.filter { it.isLight }
    fun device(id: Int) = devices.firstOrNull { it.meshId == id }

    /** Thread-safe snapshot of the local states. */
    fun snapshot(): Map<Int, LightState> = synchronized(states) { states.mapValues { it.value.copy() } }

    fun isAvailable(id: Int) = synchronized(states) { states[id]?.available == true }
    fun localState(id: Int): LightState? = synchronized(states) { states[id]?.copy() }
    val micRunning get() = mic != null

    private fun st(id: Int) = synchronized(states) { states.getOrPut(id) { LightState() } }

    private fun notifyState(id: Int) {
        val s = st(id).copy()
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
        if (!available) musicTargets.remove(id)
        availabilityListeners.forEach { it(id, available) }
        notifyState(id)
    }

    fun markAllOffline() {
        for (d in devices) setAvailable(d.meshId, false)
    }

    // ------------------------------------------------------------------ commands

    /**
     * Apply a Home Assistant style JSON command on the local mesh:
     * {"state":"ON","brightness":0..255,"color":{"r","g","b"},"color_temp":kelvin,"effect":"…"}
     */
    fun command(id: Int, root: JSONObject) {
        handler.post { applyCommand(id, root) }
    }

    private fun applyCommand(id: Int, root: JSONObject) {
        BridgeLog.i("Befehl für ${mesh.deviceName(id)}: $root")
        val st = st(id)
        var stateSet = false

        // any explicit colour / temperature / effect change ends music for this lamp
        val effect = root.optString("effect", "")
        if (musicTargets.contains(id) && (effect.isNotEmpty() && effect != EFFECT_MUSIC ||
                root.has("color") || root.has("color_temp") || root.optString("state") == "OFF")) {
            musicTargets.remove(id)
            if (st.effect == EFFECT_MUSIC) st.effect = EFFECT_NONE
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
                musicTargets.add(id)
                st.effect = effect; st.colorMode = true
                if (!musicGlobalOn) onMusicRequested?.invoke()
            }
            else -> {
                // "Kein Effekt": go back to the last static colour / white
                st.effect = EFFECT_NONE
                if (st.colorMode) mesh.setColor(id, st.r, st.g, st.b, coalesce = false)
                else mesh.setWhiteTemperature(id, st.temperature)
            }
        }
    }

    /** Speed of the colour sequences for the given lamps: tempo 0 (slow) .. 100 (fast). */
    fun applySequenceSpeed(ids: List<Int>, speed: Int) {
        val colorMs = (5000 - speed.coerceIn(0, 100) * 48).coerceAtLeast(200)
        val fadeMs = colorMs / 2
        ids.forEach {
            mesh.setSequenceColorDuration(it, colorMs)
            mesh.setSequenceFadeDuration(it, fadeMs)
        }
    }

    // ------------------------------------------------------------------ music (distributed)

    /**
     * Global music mode changed. [targets] are the lamps this bridge owns and that are
     * selected for the music mode.
     */
    fun setMusicMode(on: Boolean, targets: List<Int>) {
        handler.post {
            musicGlobalOn = on
            if (on) {
                for (id in targets) {
                    if (musicTargets.add(id)) {
                        st(id).apply { effect = EFFECT_MUSIC; colorMode = true; this.on = true }
                        notifyState(id)
                    }
                }
                lastMusicBrightness = -1
            } else {
                val old = musicTargets.toList()
                musicTargets.clear()
                for (id in old) {
                    val st = st(id)
                    st.effect = EFFECT_NONE
                    // leave the lamp on its last music colour; HA shows it as static colour
                    notifyState(id)
                }
            }
        }
    }

    /** Add / remove a single lamp while music mode is running. */
    fun setMusicTarget(id: Int, on: Boolean) {
        handler.post {
            if (on) {
                if (musicGlobalOn && musicTargets.add(id)) {
                    st(id).apply { effect = EFFECT_MUSIC; colorMode = true; this.on = true }
                    notifyState(id)
                }
            } else if (musicTargets.remove(id)) {
                st(id).effect = EFFECT_NONE
                notifyState(id)
            }
        }
    }

    fun startMic(): Boolean {
        if (mic != null) return true
        val m = MusicMode { r, g, b, level, beat ->
            val now = System.currentTimeMillis()
            // about 4 colour updates per second (BLE mesh can't do much more), immediately on beats
            if (beat || now - lastMicFrameAt >= 240) {
                lastMicFrameAt = now
                val bright = (0x0a + level * (0x64 - 0x0a)).toInt().coerceIn(0x0a, 0x64)
                onMicFrame?.invoke(r, g, b, bright, beat)
            }
        }
        m.sensitivity = musicSensitivity
        if (!m.start()) return false
        mic = m
        return true
    }

    fun stopMic() {
        mic?.stop()
        mic = null
    }

    /** Apply one music frame (from this or another tablet) to the local music lamps. */
    fun applyMusicFrame(r: Int, g: Int, b: Int, bright: Int, beat: Boolean) {
        handler.post {
            if (musicTargets.isEmpty()) return@post
            val targets = musicTargets.filter { isAvailable(it) }
            if (targets.isEmpty()) return@post
            val all = lights.all { musicTargets.contains(it.meshId) || !isAvailable(it.meshId) }
            val dests = if (all && targets.size > 1) listOf(MeshManager.BROADCAST) else targets
            val sendBright = kotlin.math.abs(bright - lastMusicBrightness) >= 8 || beat
            for (d in dests) {
                mesh.setColor(d, r, g, b, coalesce = true)
                if (sendBright) mesh.setColorBrightness(d, bright, coalesce = true)
            }
            if (sendBright) lastMusicBrightness = bright
            for (t in targets) st(t).apply { this.r = r; this.g = g; this.b = b; colorBrightness = bright }
        }
    }

    fun shutdown() {
        stopMic()
        musicTargets.clear()
    }
}
