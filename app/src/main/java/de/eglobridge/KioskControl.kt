package de.eglobridge

import java.util.concurrent.CopyOnWriteArrayList

/** Glue between the kiosk screen (activity) and the bridge service / Home Assistant. */
object KioskControl {
    interface Ui {
        fun reload()
        fun loadUrl(url: String)
        fun setScreen(on: Boolean)
        fun setBrightness(percent: Int)
        fun showUpdate(info: Updater.Info)
    }

    /** The visible kiosk activity, if any. Calls must be made on the main thread. */
    @Volatile var ui: Ui? = null
    @Volatile var screenOn = true
        set(v) { field = v; changed() }
    @Volatile var visible = false
        set(v) { field = v; changed() }

    /** Called when kiosk state changed (screen on/off, visibility) so MQTT can publish it. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun changed() = listeners.forEach { try { it() } catch (_: Throwable) { } }
}
