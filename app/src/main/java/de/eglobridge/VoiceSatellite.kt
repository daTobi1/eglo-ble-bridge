package de.eglobridge

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import de.eglobridge.esphome.ApiDevice
import de.eglobridge.esphome.ApiServer
import de.eglobridge.esphome.DeviceIdentity
import de.eglobridge.esphome.MediaFormat
import de.eglobridge.esphome.Va
import de.eglobridge.esphome.VoiceConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** What the kiosk shows about the voice assistant (immutable snapshot, main thread). */
data class VoiceView(
    /** "idle", "listening", "thinking", "responding", "announcing", "error" */
    val phase: String,
    val heard: String = "",
    val answer: String = "",
    val timers: List<TimerView> = emptyList(),
    val ringing: Boolean = false,
)

data class TimerView(
    val id: String,
    val name: String,
    val totalSeconds: Int,
    /** SystemClock.elapsedRealtime() when the timer ends (valid while active). */
    val endsAt: Long,
    val secondsLeft: Int,
    val active: Boolean,
    val finished: Boolean,
)

/**
 * The tablet as a Home Assistant voice satellite. Speaks the ESPHome native API (see [ApiServer]),
 * so Home Assistant adopts it like a Voice PE: assist satellite with pipeline / wake word selects,
 * media player, timers, announcements and "start conversation".
 *
 * Stage 1: the wake word runs in Home Assistant (openWakeWord in the pipeline). While idle the
 * tablet streams microphone audio to Home Assistant, which listens for the wake word.
 * Text-to-speech answers are played from the URL Home Assistant sends.
 */
class VoiceSatellite(private val ctx: Context, private val version: String) : ApiDevice {

    companion object {
        const val SERVICE_TYPE = "_esphomelib._tcp"
        @Volatile var current: VoiceSatellite? = null
            private set

        /** Node name for Home Assistant: lowercase, a-z0-9 and '-', max 31 chars. */
        fun nodeName(bridgeId: String): String =
            ("eglo-" + bridgeId.lowercase().replace(Regex("[^a-z0-9]+"), "-")).trim('-').take(31).trimEnd('-')
    }

    private val cfg = Config(ctx)
    private val thread = HandlerThread("voice").also { it.start() }
    private val h = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val server = ApiServer(this, log = { BridgeLog.i(it) })
    private var nsdListener: NsdManager.RegistrationListener? = null

    // ---- pipeline state (voice thread)
    private enum class Phase { IDLE, LISTENING, THINKING, RESPONDING, ANNOUNCING }
    @Volatile private var phase = Phase.IDLE
    @Volatile private var streaming = false
    @Volatile private var subscribed = false
    @Volatile private var muted = cfg.voiceMuted
    private var runActive = false
    private var awaitingRunStart = false
    private var requestSentAt = 0L
    private var conversationId = ""
    private var continueConversation = false
    private var ttsPlaying = false
    private var heard = ""
    private var answer = ""
    private var lastError = ""
    /** Failed runs in a row; slows down retries if the pipeline is broken (e.g. no wake word engine). */
    private var errorCount = 0
    private var lastVolume = -1
    /** No microphone audio to Home Assistant before this time (speaker echo dies away). */
    @Volatile private var quietUntil = 0L
    /** Backoff after failed runs: the watchdog does not restart listening before this time. */
    private var retryAt = 0L
    /** A run start is scheduled for after the quiet time. */
    private var pendingStart = false
    /** When the current phase (other than IDLE) began; used to free a stuck satellite. */
    private var phaseSince = 0L

    // ---- audio out (voice thread)
    private var speech: MediaPlayer? = null
    private var music: MediaPlayer? = null
    private var musicPaused = false
    private var alarm: MediaPlayer? = null
    @Volatile private var mpState = Va.MP_IDLE
    @Volatile private var speakerMuted = false

    private val timers = LinkedHashMap<String, TimerView>()

    // ---- microphone -> Home Assistant
    private val sendQueue = ArrayBlockingQueue<ByteArray>(100)
    private var sender: Thread? = null
    // microphone level for the debug log (peak / average over ~10 s)
    @Volatile private var levelPeak = 0
    @Volatile private var levelSumSq = 0.0
    @Volatile private var levelCount = 0L
    private var lastLevelLog = 0L

    private val micListener = MicHub.Listener { pcm ->
        if (BridgeLog.verbose) {
            var peak = levelPeak
            var sum = 0.0
            for (v in pcm) { val a = kotlin.math.abs(v.toInt()); if (a > peak) peak = a; sum += a.toDouble() * a }
            levelPeak = peak; levelSumSq += sum; levelCount += pcm.size
            val now = SystemClock.elapsedRealtime()
            if (now - lastLevelLog > 10_000 && levelCount > 0) {
                lastLevelLog = now
                fun db(x: Double) = if (x <= 0) -99 else Math.round(20 * kotlin.math.log10(x / 32768.0)).toInt()
                BridgeLog.i("Sprach-Satellit: Mikrofonpegel Spitze ${db(levelPeak.toDouble())} dBFS, " +
                    "Mittel ${db(kotlin.math.sqrt(levelSumSq / levelCount))} dBFS" + (if (streaming) " (sendet)" else ""))
                levelPeak = 0; levelSumSq = 0.0; levelCount = 0
            }
        }
        // never send our own voice to Home Assistant (Whisper transcribed the answers)
        if (streaming && !ttsPlaying && SystemClock.elapsedRealtime() >= quietUntil) {
            val b = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                val v = pcm[i].toInt()
                b[2 * i] = (v and 0xFF).toByte()
                b[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            if (!sendQueue.offer(b)) { sendQueue.poll(); sendQueue.offer(b) }
        }
    }
    private var micOpen = false

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        current = this
        h.post {
            try {
                server.start()
            } catch (t: Throwable) {
                BridgeLog.e("Sprach-Satellit: Port ${ApiServer.DEFAULT_PORT} nicht verfügbar", t)
                return@post
            }
            sender = Thread({ sendLoop() }, "voice-send").apply { isDaemon = true; start() }
            registerNsd()
            BridgeLog.i("Sprach-Satellit bereit – in Home Assistant unter Geräte & Dienste als ESPHome-Gerät " +
                "„${cfg.bridgeName}“ hinzufügen (Port ${ApiServer.DEFAULT_PORT})")
        }
        h.post(watchdog)
    }

    fun stop() {
        if (current === this) current = null
        h.removeCallbacksAndMessages(null)
        h.post {
            stopRun()
            closeMic()
            unregisterNsd()
            server.stop()
            sender?.interrupt()
            releasePlayers()
            stopAlarm()
            publishView(null)
        }
        thread.quitSafely()
    }

    // ------------------------------------------------------------------ ApiDevice (connection thread)

    override fun identity(): DeviceIdentity = DeviceIdentity(
        name = nodeName(cfg.bridgeId),
        friendlyName = cfg.bridgeName,
        mac = cfg.voiceMac,
        model = android.os.Build.MODEL,
        manufacturer = android.os.Build.MANUFACTURER,
        projectVersion = version,
    )

    override fun voiceFeatureFlags(): Int = Va.FEATURE_VOICE_ASSISTANT or Va.FEATURE_API_AUDIO or
        Va.FEATURE_TIMERS or Va.FEATURE_ANNOUNCE or Va.FEATURE_START_CONVERSATION

    // Home Assistant converts everything to MP3 for us (ffmpeg proxy); Android plays MP3 everywhere.
    override fun mediaPlayerFormats() = listOf(
        MediaFormat("mp3", 0, 0, Va.FORMAT_DEFAULT),
        MediaFormat("mp3", 22050, 1, Va.FORMAT_ANNOUNCEMENT),
    )

    override fun mediaPlayerFeatures() = Va.MPF_PAUSE or Va.MPF_PLAY or Va.MPF_STOP or Va.MPF_VOLUME_SET or
        Va.MPF_VOLUME_MUTE or Va.MPF_VOLUME_STEP or Va.MPF_PLAY_MEDIA or Va.MPF_MEDIA_ANNOUNCE

    override fun mediaPlayerState() = Triple(mpState, DeviceInfo.mediaVolume(ctx) / 100f, speakerMuted)

    override fun micMuted() = muted

    override fun onMicMuteCommand(muted: Boolean) {
        h.post {
            this.muted = muted
            cfg.voiceMuted = muted
            BridgeLog.i(if (muted) "Sprach-Satellit: Mikrofon stumm" else "Sprach-Satellit: Mikrofon an")
            if (muted) { stopRun(); closeMic(); phase = Phase.IDLE; updateView() } else ensureListening()
            server.pushStates()
        }
    }

    override fun onMediaPlayerCommand(command: Int?, volume: Float?, url: String?, announcement: Boolean) {
        h.post {
            if (volume != null) DeviceInfo.setMediaVolume(ctx, Math.round(volume * 100))
            when (command) {
                Va.MP_CMD_PAUSE -> music?.let { if (it.isPlaying) { it.pause(); musicPaused = true } }
                Va.MP_CMD_PLAY -> music?.let { if (musicPaused) { it.start(); musicPaused = false } }
                Va.MP_CMD_TOGGLE -> music?.let {
                    if (it.isPlaying) { it.pause(); musicPaused = true } else if (musicPaused) { it.start(); musicPaused = false }
                }
                Va.MP_CMD_STOP -> { stopMusic(); stopSpeech() }
                Va.MP_CMD_MUTE -> setSpeakerMuted(true)
                Va.MP_CMD_UNMUTE -> setSpeakerMuted(false)
                Va.MP_CMD_VOLUME_UP -> DeviceInfo.setMediaVolume(ctx, DeviceInfo.mediaVolume(ctx) + 10)
                Va.MP_CMD_VOLUME_DOWN -> DeviceInfo.setMediaVolume(ctx, DeviceInfo.mediaVolume(ctx) - 10)
            }
            if (url != null) {
                if (announcement) {
                    stopRun()
                    phase = Phase.ANNOUNCING
                    wakeScreen()
                    playSpeech(listOf(url)) { afterAnnouncement(false) }
                } else {
                    playMusic(url)
                }
            }
            refreshMpState()
        }
    }

    override fun onVoiceSubscription(subscribed: Boolean) {
        h.post {
            this.subscribed = subscribed
            if (subscribed) {
                BridgeLog.i("Sprach-Satellit: Home Assistant hört zu")
                runActive = false
                awaitingRunStart = false
                errorCount = 0
                retryAt = 0L
                if (!ttsPlaying && speech == null && phase != Phase.IDLE) { phase = Phase.IDLE; updateView() }
                ensureListening()
            } else {
                streaming = false
                runActive = false
                closeMic()
            }
        }
    }

    override fun onVoiceResponse(port: Int, error: Boolean) {
        h.post {
            if (error) {
                BridgeLog.w("Sprach-Satellit: Home Assistant hat den Start abgelehnt")
                streaming = false; runActive = false
                h.postDelayed({ ensureListening() }, 5000)
            }
        }
    }

    override fun onVoiceEvent(type: Int, data: Map<String, String>) {
        h.post { handleEvent(type, data) }
    }

    override fun onTimerEvent(type: Int, id: String, name: String, totalSeconds: Int, secondsLeft: Int, active: Boolean) {
        h.post {
            when (type) {
                Va.TIMER_CANCELLED -> timers.remove(id)
                Va.TIMER_FINISHED -> {
                    timers[id] = TimerView(id, name, totalSeconds, SystemClock.elapsedRealtime(), 0, false, true)
                    BridgeLog.i("Timer ${name.ifEmpty { id }} abgelaufen")
                    wakeScreen()
                    startAlarm()
                }
                else -> timers[id] = TimerView(
                    id, name, totalSeconds, SystemClock.elapsedRealtime() + secondsLeft * 1000L,
                    secondsLeft, active, false
                )
            }
            updateView()
        }
    }

    override fun onAnnounce(mediaId: String, text: String, preannounceMediaId: String, startConversation: Boolean) {
        h.post {
            stopRun()
            phase = Phase.ANNOUNCING
            heard = ""
            answer = text
            wakeScreen()
            updateView()
            playSpeech(listOfNotNull(preannounceMediaId.ifEmpty { null }, mediaId.ifEmpty { null })) {
                afterAnnouncement(startConversation)
            }
        }
    }

    // stage 1: no wake word on the tablet; Home Assistant's pipeline (openWakeWord) listens
    override fun voiceConfiguration() = VoiceConfig(emptyList(), emptyList(), 1)

    override fun onSetActiveWakeWords(ids: List<String>) {}

    override fun onClientCountChanged(count: Int) {
        if (count == 0) h.post { streaming = false; runActive = false }
    }

    // ------------------------------------------------------------------ pipeline (voice thread)

    private fun canListen() = cfg.voiceEnabled && subscribed && !muted

    /** Start listening for the wake word unless something else is going on. */
    private fun ensureListening() {
        if (!canListen()) return
        if (runActive || pendingStart || ttsPlaying || phase == Phase.ANNOUNCING) return
        if (SystemClock.elapsedRealtime() < retryAt) return
        if (!openMic()) {
            h.postDelayed({ ensureListening() }, 15000)
            return
        }
        conversationId = ""
        phase = Phase.IDLE
        startRun(Va.REQUEST_USE_WAKE_WORD, "")
    }

    private fun startRun(flags: Int, convId: String) {
        val wait = quietUntil - SystemClock.elapsedRealtime()
        if (wait > 0) {
            if (pendingStart) return
            pendingStart = true
            h.postDelayed({
                pendingStart = false
                if (canListen() && !runActive && !ttsPlaying) startRun(flags, convId)
            }, wait)
            return
        }
        sendQueue.clear()
        streaming = true
        runActive = true
        awaitingRunStart = true
        requestSentAt = SystemClock.elapsedRealtime()
        // audio processing in Home Assistant, adjustable via config/set (default off like ESPHome)
        if (!server.sendVoiceRequest(true, convId, flags, noiseSuppression = cfg.voiceNoiseSuppression,
                autoGain = cfg.voiceAutoGain, volumeMultiplier = cfg.voiceVolumeMultiplier)) {
            streaming = false; runActive = false
        }
    }

    /** Stop the current run (if any) and stop sending audio. */
    private fun stopRun() {
        streaming = false
        if (runActive) {
            runActive = false
            server.sendVoiceRequest(false)
        }
        awaitingRunStart = false
    }

    private fun handleEvent(type: Int, data: Map<String, String>) {
        if (type == Va.EVENT_RUN_START) {
            awaitingRunStart = false
            return
        }
        // left-overs of a run we already stopped
        if (awaitingRunStart || (!runActive && type != Va.EVENT_TTS_END)) return
        when (type) {
            Va.EVENT_WAKE_WORD_END -> {
                errorCount = 0
                phase = Phase.LISTENING
                heard = ""; answer = ""; lastError = ""
                stopAlarm()
                duckMusic(true)
                wakeScreen()
            }
            Va.EVENT_STT_START -> if (phase == Phase.IDLE) phase = Phase.LISTENING
            Va.EVENT_STT_VAD_END -> { streaming = false; phase = Phase.THINKING }
            Va.EVENT_STT_END -> {
                streaming = false
                phase = Phase.THINKING
                heard = data["text"].orEmpty()
            }
            Va.EVENT_INTENT_END -> {
                errorCount = 0
                conversationId = data["conversation_id"].orEmpty()
                continueConversation = data["continue_conversation"] == "1"
                data["speech"]?.takeIf { it.isNotBlank() }?.let { answer = it }
            }
            Va.EVENT_TTS_START -> data["text"]?.takeIf { it.isNotBlank() }?.let { answer = it }
            Va.EVENT_TTS_END -> {
                val url = data["url"]
                if (!url.isNullOrEmpty()) {
                    phase = Phase.RESPONDING
                    playSpeech(listOf(url)) {
                        server.sendAnnounceFinished(true)
                        afterRun()
                    }
                }
            }
            Va.EVENT_ERROR -> {
                // the run is over; Home Assistant may or may not send RUN_END afterwards
                streaming = false
                runActive = false
                val code = data["code"].orEmpty()
                if (code != "no_wake_word") {
                    BridgeLog.w("Sprach-Satellit: Fehler von Home Assistant: $code ${data["message"].orEmpty()}")
                    // nothing said after the wake word / follow-up question: no error on screen
                    if (code != "stt-no-text-recognized") lastError = data["message"].orEmpty().ifEmpty { code }
                    errorCount++
                }
                h.postDelayed({ afterRun() }, 300)
            }
            Va.EVENT_RUN_END -> {
                runActive = false
                streaming = false
                if (!ttsPlaying) afterRun()
            }
        }
        updateView()
    }

    private fun afterRun() {
        if (runActive || ttsPlaying) return
        if (continueConversation && conversationId.isNotEmpty() && canListen()) {
            continueConversation = false
            phase = Phase.LISTENING
            heard = ""
            updateView()
            startRun(0, conversationId)
            return
        }
        continueConversation = false
        duckMusic(false)
        val hadError = lastError.isNotEmpty()
        phase = Phase.IDLE
        updateView()
        // the answer stays on screen for a moment
        h.postDelayed({ if (phase == Phase.IDLE) { heard = ""; answer = ""; lastError = ""; updateView() } },
            if (hadError) 6000 else 8000)
        // a broken pipeline should not hammer Home Assistant: 3 s, 6 s, 12 s … up to 2 min
        if (errorCount > 0) {
            val delay = (3000L shl (errorCount - 1).coerceAtMost(6)).coerceAtMost(120_000L)
            retryAt = SystemClock.elapsedRealtime() + delay
            h.postDelayed({ ensureListening() }, delay)
        } else {
            retryAt = 0L
            ensureListening()
        }
    }

    private fun afterAnnouncement(startConversation: Boolean) {
        server.sendAnnounceFinished(true)
        if (startConversation && canListen() && openMic()) {
            phase = Phase.LISTENING
            heard = ""
            duckMusic(true)
            updateView()
            startRun(0, "")
        } else {
            phase = Phase.IDLE
            refreshMpState()
            afterRun()
        }
    }

    /** Restarts a stuck pipeline request and keeps the countdown display fresh. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (awaitingRunStart && SystemClock.elapsedRealtime() - requestSentAt > 15000) {
                BridgeLog.w("Sprach-Satellit: keine Antwort von Home Assistant – neuer Versuch")
                stopRun()
                ensureListening()
            } else if (!runActive && !ttsPlaying && phase == Phase.IDLE && canListen()) {
                ensureListening()
            } else if (!runActive && !ttsPlaying && speech == null && phase != Phase.IDLE &&
                SystemClock.elapsedRealtime() - phaseSince > 30_000) {
                // e.g. playback stopped from outside during an announcement: nothing will end this phase
                BridgeLog.w("Sprach-Satellit: hing in Phase ${phase.name} – zurück auf Zuhören")
                phase = Phase.IDLE
                errorCount = 0
                retryAt = 0L
                refreshMpState()
                updateView()
                ensureListening()
            }
            // volume changed on the tablet itself (buttons, MQTT): tell Home Assistant
            val vol = DeviceInfo.mediaVolume(ctx)
            if (vol != lastVolume) { lastVolume = vol; server.pushStates() }
            h.postDelayed(this, 10000)
        }
    }

    // ------------------------------------------------------------------ microphone

    private fun openMic(): Boolean {
        if (micOpen) return true
        micOpen = MicHub.add(micListener)
        if (!micOpen) BridgeLog.e("Sprach-Satellit: Mikrofon nicht verfügbar")
        return micOpen
    }

    private fun closeMic() {
        if (!micOpen) return
        MicHub.remove(micListener)
        micOpen = false
    }

    private fun sendLoop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val chunk = sendQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (streaming) server.sendVoiceAudio(chunk)
            }
        } catch (_: InterruptedException) { }
    }

    // ------------------------------------------------------------------ audio out (voice thread)

    private fun newPlayer(): MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        if (speakerMuted) setVolume(0f, 0f)
    }

    /** Plays the URLs one after another, then calls [done] (also on errors). */
    private fun playSpeech(urls: List<String>, done: () -> Unit) {
        stopSpeech()
        if (urls.isEmpty()) { done(); return }
        ttsPlaying = true
        refreshMpState()
        updateView()
        val p = newPlayer()
        speech = p
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            h.removeCallbacks(guard)
            if (speech === p) speech = null
            try { p.release() } catch (_: Throwable) { }
            val rest = urls.drop(1)
            if (rest.isNotEmpty()) { playSpeech(rest, done); return }
            ttsPlaying = false
            quietUntil = SystemClock.elapsedRealtime() + 700
            refreshMpState()
            done()
        }
        p.setOnPreparedListener { it.start() }
        p.setOnCompletionListener { finish() }
        p.setOnErrorListener { _, what, extra ->
            BridgeLog.w("Sprach-Satellit: Wiedergabe fehlgeschlagen ($what/$extra): ${urls.first()}")
            finish(); true
        }
        speechGuard = { BridgeLog.w("Sprach-Satellit: Wiedergabe hängt – abgebrochen"); finish() }
        h.postDelayed(guard, 120_000)
        try {
            p.setDataSource(urls.first())
            p.prepareAsync()
        } catch (t: Throwable) {
            BridgeLog.e("Sprach-Satellit: Wiedergabe nicht möglich", t)
            finish()
        }
    }

    private var speechGuard: (() -> Unit)? = null
    private val guard = Runnable { speechGuard?.invoke() }

    private fun stopSpeech() {
        val p = speech ?: return
        speech = null
        h.removeCallbacks(guard)
        try { p.stop() } catch (_: Throwable) { }
        try { p.release() } catch (_: Throwable) { }
        ttsPlaying = false
        quietUntil = SystemClock.elapsedRealtime() + 700
    }

    private fun playMusic(url: String) {
        stopMusic()
        val p = newPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        )
        music = p
        musicPaused = false
        p.setOnPreparedListener { it.start(); refreshMpState() }
        p.setOnCompletionListener { if (music === p) { stopMusic(); refreshMpState() } }
        p.setOnErrorListener { _, what, extra ->
            BridgeLog.w("Media Player: Wiedergabe fehlgeschlagen ($what/$extra)")
            if (music === p) stopMusic(); refreshMpState(); true
        }
        try {
            p.setDataSource(url)
            p.prepareAsync()
        } catch (t: Throwable) {
            BridgeLog.e("Media Player: Wiedergabe nicht möglich", t)
            stopMusic()
        }
    }

    private fun stopMusic() {
        val p = music ?: return
        music = null
        musicPaused = false
        try { p.stop() } catch (_: Throwable) { }
        try { p.release() } catch (_: Throwable) { }
    }

    private fun duckMusic(on: Boolean) {
        val p = music ?: return
        if (speakerMuted) return
        try { if (on) p.setVolume(0.15f, 0.15f) else p.setVolume(1f, 1f) } catch (_: Throwable) { }
    }

    private fun setSpeakerMuted(m: Boolean) {
        speakerMuted = m
        val v = if (m) 0f else 1f
        for (p in listOfNotNull(speech, music)) try { p.setVolume(v, v) } catch (_: Throwable) { }
    }

    private fun refreshMpState() {
        val s = when {
            // Home Assistant's ESPHome media player has no mapping for MP_ANNOUNCING (KeyError,
            // the satellite then hung) – report speech output as PLAYING like music
            ttsPlaying || phase == Phase.ANNOUNCING -> Va.MP_PLAYING
            music != null && musicPaused -> Va.MP_PAUSED
            music != null -> Va.MP_PLAYING
            else -> Va.MP_IDLE
        }
        mpState = s
        server.pushStates()
    }

    private fun releasePlayers() {
        stopSpeech()
        stopMusic()
    }

    // ------------------------------------------------------------------ timer alarm

    private val alarmTimeout = Runnable { stopAlarm() }

    private fun startAlarm() {
        if (alarm != null) return
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            p.setDataSource(ctx, uri)
            p.isLooping = true
            p.setOnPreparedListener { it.start() }
            p.prepareAsync()
            alarm = p
        } catch (t: Throwable) {
            BridgeLog.w("Timer-Ton nicht verfügbar: ${t.message}")
        }
        h.removeCallbacks(alarmTimeout)
        h.postDelayed(alarmTimeout, 90_000)
        updateView()
    }

    /** Stops the ringing timer (tap on the kiosk overlay, new wake word, or after 90 s). */
    fun stopAlarm() {
        h.post {
            h.removeCallbacks(alarmTimeout)
            val p = alarm
            alarm = null
            if (p != null) {
                try { p.stop() } catch (_: Throwable) { }
                try { p.release() } catch (_: Throwable) { }
            }
            timers.values.removeAll { it.finished }
            updateView()
        }
    }

    // ------------------------------------------------------------------ kiosk overlay

    private var lastPhase = Phase.IDLE

    private fun updateView() {
        if (phase != lastPhase) { lastPhase = phase; phaseSince = SystemClock.elapsedRealtime() }
        val phaseName = when {
            lastError.isNotEmpty() && phase == Phase.IDLE -> "error"
            else -> phase.name.lowercase()
        }
        val v = VoiceView(
            phase = phaseName,
            heard = heard,
            answer = if (phaseName == "error") lastError else answer,
            timers = timers.values.toList(),
            ringing = alarm != null,
        )
        publishView(v)
    }

    private fun publishView(v: VoiceView?) {
        KioskControl.voice = v
        main.post { KioskControl.ui?.showVoice(v) }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        if (KioskControl.screenOn && KioskControl.visible) return
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "eglobridge:voice")
                .acquire(5000)
        } catch (_: Throwable) { }
        KioskControl.wakeRequested = true
        main.post { KioskControl.ui?.let { it.setScreen(true); KioskControl.wakeRequested = false } }
    }

    // ------------------------------------------------------------------ mDNS (Home Assistant discovery)

    private fun registerNsd() {
        try {
            val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
            val id = identity()
            val info = NsdServiceInfo().apply {
                serviceName = id.name
                serviceType = SERVICE_TYPE
                port = ApiServer.DEFAULT_PORT
                setAttribute("mac", id.mac.replace(":", "").lowercase())
                setAttribute("version", id.esphomeVersion)
                setAttribute("friendly_name", id.friendlyName)
                setAttribute("platform", "Android")
                setAttribute("board", id.model)
                setAttribute("network", "wifi")
                setAttribute("project_name", id.projectName)
                setAttribute("project_version", id.projectVersion)
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(s: NsdServiceInfo) {
                    BridgeLog.i("Sprach-Satellit im Netz angemeldet als „${s.serviceName}“")
                }
                override fun onRegistrationFailed(s: NsdServiceInfo, errorCode: Int) {
                    BridgeLog.w("Sprach-Satellit: mDNS-Anmeldung fehlgeschlagen ($errorCode) – in HA manuell mit IP und Port 6053 hinzufügen")
                }
                override fun onServiceUnregistered(s: NsdServiceInfo) {}
                override fun onUnregistrationFailed(s: NsdServiceInfo, errorCode: Int) {}
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsdListener = l
        } catch (t: Throwable) {
            BridgeLog.w("Sprach-Satellit: mDNS nicht verfügbar: ${t.message}")
        }
    }

    private fun unregisterNsd() {
        val l = nsdListener ?: return
        nsdListener = null
        try { (ctx.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) } catch (_: Throwable) { }
    }
}
