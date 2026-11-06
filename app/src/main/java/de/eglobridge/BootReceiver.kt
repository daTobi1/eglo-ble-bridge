package de.eglobridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cfg = Config(context)
        if (cfg.enabled && cfg.isComplete) {
            BridgeLog.i("Autostart (${intent.action})")
            BridgeService.start(context)
        }
    }
}
