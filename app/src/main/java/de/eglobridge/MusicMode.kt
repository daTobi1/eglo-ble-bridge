package de.eglobridge

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Listens to the microphone and turns music into light:
 * - loudness -> brightness (automatic gain)
 * - spectrum (bass / mid / treble) -> hue
 * - beats -> hue jumps
 * Calls [onFrame] roughly every [FRAME_MS] ms from its own thread.
 */
class MusicMode(private val onFrame: (r: Int, g: Int, b: Int, level: Float, beat: Boolean) -> Unit) {

    companion object {
        private const val RATE = 16000
        private const val N = 1024          // ~64 ms per FFT frame
        const val FRAME_MS = 64L
    }

    @Volatile var sensitivity = 1.0f      // 0.5 .. 2.0
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning get() = running

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { BridgeLog.e("Mikrofon: ungültige Puffergröße"); return false }
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, N * 4))
        } catch (t: Throwable) {
            BridgeLog.e("Mikrofon nicht verfügbar", t); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            BridgeLog.e("Mikrofon konnte nicht geöffnet werden (Berechtigung?)")
            rec.release(); return false
        }
        running = true
        thread = Thread({ loop(rec) }, "music").also { it.start() }
        BridgeLog.i("Musikmodus gestartet")
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.join(500)
        thread = null
        BridgeLog.i("Musikmodus gestoppt")
    }

    private fun loop(rec: AudioRecord) {
        val buf = ShortArray(N)
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        val window = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
        var peak = 1e-3
        var avgEnergy = 0.0
        var smoothLevel = 0.0
        var baseHue = 0.0
        var smoothHue = 0.0
        var lastBeat = 0L
        try {
            rec.startRecording()
            while (running) {
                var read = 0
                while (read < N && running) {
                    val n = rec.read(buf, read, N - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < N) { Thread.sleep(50); continue }

                var sum = 0.0
                for (i in 0 until N) {
                    val s = buf[i] / 32768.0
                    sum += s * s
                    re[i] = s * window[i]
                    im[i] = 0.0
                }
                val rms = sqrt(sum / N) * sensitivity
                fft(re, im)

                // band energies (bin width = RATE / N = 15.6 Hz)
                var bass = 0.0; var mid = 0.0; var high = 0.0
                for (k in 1 until N / 2) {
                    val mag = re[k] * re[k] + im[k] * im[k]
                    val f = k * RATE.toDouble() / N
                    when {
                        f < 250 -> bass += mag
                        f < 2000 -> mid += mag
                        else -> high += mag
                    }
                }
                // perceptual weighting so treble is not drowned by bass
                mid *= 2.0; high *= 6.0
                val total = bass + mid + high + 1e-12

                // automatic gain: slowly decaying peak
                peak = max(peak * 0.997, rms)
                val level = (rms / peak).coerceIn(0.0, 1.0)
                // compress dynamics a little (log-ish)
                val lv = (ln(1 + 9 * level) / ln(10.0))
                smoothLevel = if (lv > smoothLevel) lv else smoothLevel * 0.8 + lv * 0.2

                // beat: bass energy clearly above its running average
                val now = System.currentTimeMillis()
                val beat = bass > avgEnergy * 1.6 && bass > 1e-4 && now - lastBeat > 250
                avgEnergy = avgEnergy * 0.95 + bass * 0.05
                if (beat) { lastBeat = now; baseHue = (baseHue + 47) % 360 }

                val bandHue = (0.0 * bass + 120.0 * mid + 240.0 * high) / total
                val targetHue = (baseHue + bandHue) % 360
                // move around the circle the short way
                var d = targetHue - smoothHue
                if (d > 180) d -= 360
                if (d < -180) d += 360
                smoothHue = ((smoothHue + d * (if (beat) 1.0 else 0.35)) + 360) % 360

                val (r, g, b) = hsvToRgb(smoothHue, 1.0, 1.0)
                onFrame(r, g, b, smoothLevel.toFloat(), beat)
            }
        } catch (t: Throwable) {
            BridgeLog.e("Musikmodus-Fehler", t)
        } finally {
            try { rec.stop() } catch (_: Throwable) { }
            rec.release()
            running = false
        }
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

fun hsvToRgb(h: Double, s: Double, v: Double): Triple<Int, Int, Int> {
    val c = v * s
    val hh = (h % 360) / 60.0
    val x = c * (1 - kotlin.math.abs(hh % 2 - 1))
    val (r1, g1, b1) = when (hh.toInt()) {
        0 -> Triple(c, x, 0.0)
        1 -> Triple(x, c, 0.0)
        2 -> Triple(0.0, c, x)
        3 -> Triple(0.0, x, c)
        4 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    val m = v - c
    return Triple(((r1 + m) * 255).toInt(), ((g1 + m) * 255).toInt(), ((b1 + m) * 255).toInt())
}
