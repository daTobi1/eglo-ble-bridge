package de.eglobridge

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

/**
 * The one microphone of the tablet, shared by the music mode and the voice satellite.
 * Android hands the microphone to only one recorder at a time, so both listen here instead of
 * opening their own AudioRecord. Delivers 16 kHz mono 16-bit PCM in chunks of [CHUNK] samples
 * (32 ms) on the "mic" thread; listeners must return quickly.
 *
 * A watchdog ("mic-watch") keeps it alive: if the open microphone stops delivering samples, it is
 * closed and reopened; if it cannot be opened, it is retried. Errors are repeated in the log every
 * few minutes instead of only once, so a deaf tablet shows up in Home Assistant.
 */
object MicHub {
    const val RATE = 16000
    const val CHUNK = 512

    fun interface Listener {
        fun onAudio(pcm: ShortArray)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var thread: Thread? = null
    @Volatile private var record: AudioRecord? = null

    /**
     * Registers a listener and opens the microphone if needed. Returns false if the microphone
     * cannot be opened right now. The listener stays registered either way: the watchdog keeps
     * trying and the listener gets audio as soon as it works. Call [remove] to stop listening.
     */
    @Synchronized
    fun add(l: Listener): Boolean {
        if (!listeners.contains(l)) listeners.add(l)
        startWatchdog()
        if (record == null && !open()) {
            nextOpenTry = SystemClock.elapsedRealtime() + MicHealth.RETRY_MS
            return false
        }
        return true
    }

    @Synchronized
    fun remove(l: Listener) {
        listeners.remove(l)
        if (listeners.isEmpty()) close()
    }

    val isOpen get() = record != null

    /** True while the microphone cannot be opened at all. */
    @Volatile var blocked = false
        private set
    /** Last error text, for Home Assistant. */
    @Volatile var lastError = ""
        private set
    private var failingSince = 0L
    private var lastFailLog = 0L

    private fun failed(msg: String, t: Throwable? = null) {
        val now = SystemClock.elapsedRealtime()
        lastError = msg + (t?.message?.let { ": $it" } ?: "")
        if (!blocked) {
            failingSince = now; lastFailLog = now
            if (t != null) BridgeLog.e(msg, t) else BridgeLog.e(msg)
        } else if (MicHealth.shouldRepeatLog(now - lastFailLog)) {
            // the watchdog retries quietly – repeat the error now and then so it is not forgotten
            lastFailLog = now
            BridgeLog.e("Mikrofon weiterhin nicht verfügbar (seit ${(now - failingSince) / 60_000} min): $lastError")
        }
        blocked = true
    }

    /** True while the microphone delivers nothing but digital silence (Android blocks it). */
    @Volatile var silent = false
        private set
    private var silentChunks = 0
    @Volatile private var lastReopen = 0L

    /** When samples last arrived / the microphone was last opened (elapsedRealtime). */
    @Volatile private var lastAudioAt = 0L
    @Volatile private var openedAt = 0L
    /** Automatic reopens since the app started (shown in Home Assistant). */
    @Volatile var reopenCount = 0
        private set

    /** Open, but no samples for a few seconds (hanging audio driver or a dead recording thread). */
    val stalled: Boolean
        get() {
            val now = SystemClock.elapsedRealtime()
            return MicHealth.isStalled(record != null, now - lastAudioAt, now - openedAt)
        }

    /** Health for Home Assistant, see [MicHealth]. */
    fun status(): String {
        val now = SystemClock.elapsedRealtime()
        return MicHealth.classify(listeners.isNotEmpty(), record != null, blocked, silent,
            now - lastAudioAt, now - openedAt)
    }

    /** Close and reopen the microphone (keeps the listeners), e.g. after Android granted access again. */
    fun reopenLater(delayMs: Long = 700) {
        Thread({
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
            reopen()
        }, "mic-reopen").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun reopen() {
        if (listeners.isEmpty()) return
        lastReopen = SystemClock.elapsedRealtime()
        close()
        if (!open()) {
            nextOpenTry = SystemClock.elapsedRealtime() + MicHealth.RETRY_MS
            BridgeLog.e("Mikrofon: erneutes Öffnen fehlgeschlagen")
        }
    }

    // ------------------------------------------------------------------ watchdog

    @Volatile private var watchdog: Thread? = null
    private var nextOpenTry = 0L
    private var lastStallLog = 0L

    private fun startWatchdog() {
        if (watchdog != null) return
        watchdog = Thread({
            while (true) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                try { tick() } catch (t: Throwable) { BridgeLog.e("Mikrofon-Wächter", t) }
            }
        }, "mic-watch").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun tick() {
        if (listeners.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (record == null) {
            // could not be opened (or was closed after an error): try again now and then
            if (now >= nextOpenTry) {
                if (!open()) nextOpenTry = now + MicHealth.RETRY_MS
            }
            return
        }
        if (!stalled || !MicHealth.mayReopen(now - lastReopen)) return
        reopenCount++
        val quiet = (now - lastAudioAt) / 1000
        if (lastStallLog == 0L || MicHealth.shouldRepeatLog(now - lastStallLog)) {
            lastStallLog = now
            BridgeLog.w("Mikrofon liefert seit $quiet s keine Daten – wird neu geöffnet (Nr. $reopenCount)")
        }
        lastReopen = now
        close()
        if (!open()) nextOpenTry = now + MicHealth.RETRY_MS
    }

    // ------------------------------------------------------------------ AudioRecord

    /**
     * Android audio source: "voice_recognition" (default, no automatic gain – clean for speech
     * recognition), "mic" (with the device's automatic gain, clips on some tablets) or "unprocessed".
     * Takes effect the next time the microphone is opened.
     */
    @Volatile var source: String = "voice_recognition"

    private fun sourceId(name: String): Int = when (name) {
        "mic" -> MediaRecorder.AudioSource.MIC
        "unprocessed" -> if (android.os.Build.VERSION.SDK_INT >= 24) MediaRecorder.AudioSource.UNPROCESSED
            else MediaRecorder.AudioSource.VOICE_RECOGNITION
        else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    /** The configured source first, then the ones every Android device has. */
    private fun candidates(): List<String> =
        listOf(source, "voice_recognition", "mic").distinct()

    /** Source actually in use (may differ from [source] when that one is not supported). */
    @Volatile var activeSource: String = ""
        private set
    private var warnedSource = ""

    @SuppressLint("MissingPermission")
    private fun open(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { failed("Mikrofon: ungültige Puffergröße"); return false }
        // A source the tablet does not support (e.g. "unprocessed" on the SM-P600) makes AudioRecord
        // fail to initialise. It only takes effect when the microphone is opened next, i.e. often
        // after the next restart – so fall back to a working source instead of staying deaf.
        var rec: AudioRecord? = null
        var used = ""
        for (name in candidates()) {
            val r = try {
                AudioRecord(sourceId(name), RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minBuf, CHUNK * 2 * 8))
            } catch (t: Throwable) { null }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { rec = r; used = name; break }
            r?.release()
        }
        if (rec == null) {
            failed("Mikrofon konnte nicht geöffnet werden (belegt oder keine Berechtigung? Quelle: $source)")
            return false
        }
        try { rec.startRecording() } catch (t: Throwable) {
            rec.release(); failed("Mikrofon-Aufnahme startet nicht", t); return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release(); failed("Mikrofon-Aufnahme startet nicht (Android verweigert)"); return false
        }
        if (used != source && warnedSource != source) {
            warnedSource = source
            BridgeLog.w("Mikrofon-Quelle „$source“ wird von diesem Gerät nicht unterstützt – nutze „$used“")
        }
        if (blocked) BridgeLog.i("Mikrofon wieder verfügbar")
        blocked = false
        lastError = ""
        activeSource = used
        record = rec
        silent = false
        silentChunks = 0
        val now = SystemClock.elapsedRealtime()
        openedAt = now
        lastAudioAt = now
        BridgeLog.i("Mikrofon geöffnet (Quelle: $used)")
        val r = rec
        thread = Thread({ loop(r) }, "mic").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    private fun close() {
        val rec = record ?: return
        record = null
        // stop() first: it also wakes a read() that hangs in the audio driver
        try { rec.stop() } catch (_: Throwable) { }
        thread?.join(500)
        thread = null
        rec.release()
    }

    /**
     * Android 11 silences the microphone of a background-started service (all samples exactly 0).
     * After ~5 s of that: log it once and try to reopen now and then (helps once the app was visible).
     */
    private fun checkSilence(buf: ShortArray) {
        var zero = true
        for (v in buf) if (v.toInt() != 0) { zero = false; break }
        if (!zero) {
            if (silent) BridgeLog.i("Mikrofon liefert wieder Ton")
            silent = false; silentChunks = 0
            return
        }
        silentChunks++
        if (silentChunks == 156 && !silent) {
            silent = true
            BridgeLog.w("Mikrofon liefert nur Stille – Android sperrt es im Hintergrund. " +
                "Die App bzw. den Kiosk einmal in den Vordergrund holen.")
        }
        if (silent && SystemClock.elapsedRealtime() - lastReopen > 60_000) {
            lastReopen = SystemClock.elapsedRealtime()
            reopenLater(0)
        }
    }

    private fun loop(rec: AudioRecord) {
        try {
            while (record === rec) {
                val buf = ShortArray(CHUNK)
                var read = 0
                while (read < CHUNK && record === rec) {
                    val n = rec.read(buf, read, CHUNK - read)
                    if (n <= 0) { Thread.sleep(20); break }
                    read += n
                    lastAudioAt = SystemClock.elapsedRealtime()
                }
                if (read < CHUNK) continue
                checkSilence(buf)
                for (l in listeners) {
                    try { l.onAudio(buf) } catch (t: Throwable) { BridgeLog.e("Mikrofon-Empfänger", t) }
                }
            }
        } catch (t: Throwable) {
            // the watchdog notices the missing samples and reopens
            if (record === rec) BridgeLog.e("Mikrofon-Fehler", t)
        }
    }
}
