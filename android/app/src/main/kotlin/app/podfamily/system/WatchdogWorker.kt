package app.podfamily.system

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Every 15 minutes: if the service was killed (aggressive OEM battery savers do this), bring it back. */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        PermissionWatch.check(applicationContext)
        ServiceController.ensureRunning(applicationContext, "watchdog")
        // Rate-limited inside (every 6 h); posts one notification per new version.
        app.podfamily.update.Updater.check(applicationContext)
        return Result.success()
    }
}
