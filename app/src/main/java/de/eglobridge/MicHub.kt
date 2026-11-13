package de.eglobridge

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

/**
 * The one microphone of the tablet, shared by the music mode and the voice satellite.
 * Android hands the microphone to only one recorder at a time, so both listen here instead of
 * opening their own AudioRecord. Delivers 16 kHz mono 16-bit PCM in chunks of [CHUNK] samples
 * (32 ms) on the "mic" thread; listeners must return quickly.
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

    /** Adds a listener and opens the microphone if needed. Returns false if the microphone cannot be opened. */
    @Synchronized
    fun add(l: Listener): Boolean {
        if (record == null && !open()) return false
        if (!listeners.contains(l)) listeners.add(l)
        return true
    }

    @Synchronized
    fun remove(l: Listener) {
        listeners.remove(l)
        if (listeners.isEmpty()) close()
    }

    val isOpen get() = record != null

    /** True while the microphone delivers nothing but digital silence (Android blocks it). */
    @Volatile var silent = false
        private set
    private var silentChunks = 0
    @Volatile private var lastReopen = 0L

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
        lastReopen = System.currentTimeMillis()
        close()
        if (!open()) BridgeLog.e("Mikrofon: erneutes Öffnen fehlgeschlagen")
    }

    /**
     * Android audio source: "voice_recognition" (default, no automatic gain – clean for speech
     * recognition), "mic" (with the device's automatic gain, clips on some tablets) or "unprocessed".
     * Takes effect the next time the microphone is opened.
     */
    @Volatile var source: String = "voice_recognition"

    private fun sourceId(): Int = when (source) {
        "mic" -> MediaRecorder.AudioSource.MIC
        "unprocessed" -> if (android.os.Build.VERSION.SDK_INT >= 24) MediaRecorder.AudioSource.UNPROCESSED
            else MediaRecorder.AudioSource.VOICE_RECOGNITION
        else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    @SuppressLint("MissingPermission")
    private fun open(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { BridgeLog.e("Mikrofon: ungültige Puffergröße"); return false }
        val rec = try {
            AudioRecord(sourceId(), RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, CHUNK * 2 * 8))
        } catch (t: Throwable) {
            BridgeLog.e("Mikrofon nicht verfügbar", t); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            BridgeLog.e("Mikrofon konnte nicht geöffnet werden (Berechtigung?)")
            rec.release(); return false
        }
        try { rec.startRecording() } catch (t: Throwable) {
            BridgeLog.e("Mikrofon-Aufnahme startet nicht", t); rec.release(); return false
        }
        record = rec
        silent = false
        silentChunks = 0
        BridgeLog.i("Mikrofon geöffnet (Quelle: $source)")
        thread = Thread({ loop(rec) }, "mic").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    private fun close() {
        val rec = record ?: return
        record = null
        thread?.join(500)
        thread = null
        try { rec.stop() } catch (_: Throwable) { }
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
        if (silent && System.currentTimeMillis() - lastReopen > 60_000) {
            lastReopen = System.currentTimeMillis()
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
                }
                if (read < CHUNK) continue
                checkSilence(buf)
                for (l in listeners) {
                    try { l.onAudio(buf) } catch (t: Throwable) { BridgeLog.e("Mikrofon-Empfänger", t) }
                }
            }
        } catch (t: Throwable) {
            BridgeLog.e("Mikrofon-Fehler", t)
        }
    }
}
