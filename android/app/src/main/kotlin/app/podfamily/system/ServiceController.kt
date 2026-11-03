package app.podfamily.system

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.podfamily.data.PodRepository
import app.podfamily.location.LocationService
import java.util.concurrent.TimeUnit

object ServiceController {
    // Renamed when the code moved to a new package: WorkManager stores the worker's class name,
    // so the old job can't run anymore. It's cancelled by name below.
    private const val WATCHDOG = "pod-watchdog-2"
    private const val OLD_WATCHDOG = "pod-watchdog"

    /** Starts the location service if this phone is in a family and has permission. Safe to call often. */
    fun ensureRunning(context: Context, reason: String, force: Boolean = false) {
        val ctx = context.applicationContext
        val repo = PodRepository.get(ctx)
        if (!repo.isInFamily || !Permissions.hasBackgroundLocation(ctx)) return
        scheduleWatchdog(ctx)
        if (LocationService.isRunning && !force) return
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LocationService::class.java))
            DiagLog.i("Service start requested ($reason)")
        } catch (e: Exception) {
            // Android 12+ blocks starting foreground services from the background
            // unless battery optimization is off. Ask the user to tap to resume.
            DiagLog.w("Could not start service ($reason): ${e.javaClass.simpleName}")
            Notifications.show(
                ctx, Notifications.CH_FAMILY, "Pod needs a tap",
                "Location sharing stopped. Tap to resume. Setting Pod's battery usage to \"Unrestricted\" prevents this.",
                Notifications.ID_RESTART,
                onlyAlertOnce = true,
            )
        }
    }

    fun stop(context: Context) {
        val ctx = context.applicationContext
        ctx.stopService(Intent(ctx, LocationService::class.java))
        WorkManager.getInstance(ctx).cancelUniqueWork(WATCHDOG)
    }

    private fun scheduleWatchdog(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(OLD_WATCHDOG)
        val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WATCHDOG, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
