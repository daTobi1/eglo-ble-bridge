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

    @SuppressLint("MissingPermission")
    private fun open(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { BridgeLog.e("Mikrofon: ungültige Puffergröße"); return false }
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
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
                for (l in listeners) {
                    try { l.onAudio(buf) } catch (t: Throwable) { BridgeLog.e("Mikrofon-Empfänger", t) }
                }
            }
        } catch (t: Throwable) {
            BridgeLog.e("Mikrofon-Fehler", t)
        }
    }
}
