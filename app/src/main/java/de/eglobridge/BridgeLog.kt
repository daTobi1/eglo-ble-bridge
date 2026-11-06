package de.eglobridge

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Small in-memory ring buffer log that the UI and the MQTT log sensor can read. */
object BridgeLog {
    private const val TAG = "EgloBridge"
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)
    val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    fun i(msg: String) = add("I", msg)
    fun w(msg: String) = add("W", msg)
    fun e(msg: String, t: Throwable? = null) =
        add("E", if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg)

    private fun add(level: String, msg: String) {
        when (level) {
            "E" -> Log.e(TAG, msg)
            "W" -> Log.w(TAG, msg)
            else -> Log.i(TAG, msg)
        }
        val line = "${fmt.format(Date())} $level $msg"
        synchronized(this) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        for (l in listeners) {
            try { l(line) } catch (_: Throwable) { }
        }
    }
}
