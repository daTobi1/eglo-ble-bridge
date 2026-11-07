package de.eglobridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cfg = Config(context)
        if (!(cfg.enabled && cfg.isComplete)) return
        BridgeLog.i("Autostart (${intent.action})")
        // A foreground service started from the background is not allowed to use the
        // microphone. With the overlay permission we may open our activity for a moment:
        // it starts the service from the foreground and then hands back to the kiosk app.
        if (Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_AUTOSTART, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
                return
            } catch (t: Throwable) {
                BridgeLog.w("Autostart über die App nicht möglich: ${t.message}")
            }
        }
        BridgeService.start(context)
    }
}
