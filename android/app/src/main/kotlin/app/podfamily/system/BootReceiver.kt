package app.podfamily.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts sharing after a reboot or an app update, with no need to open the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                ServiceController.ensureRunning(context, intent.action ?: "boot")
        }
    }
}
