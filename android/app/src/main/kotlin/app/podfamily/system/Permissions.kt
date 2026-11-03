package app.podfamily.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

object Permissions {
    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasFine(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Location allowed, but only "Approximate" (Android blurs it by about 2 km). */
    fun isApproximateOnly(ctx: Context) = !hasFine(ctx) && granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasFineOrCoarse(ctx: Context) = hasFine(ctx) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackgroundLocation(ctx: Context) =
        hasFineOrCoarse(ctx) && (Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION))

    fun hasNotifications(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Everything Pod needs to work reliably in the background. */
    fun allCritical(ctx: Context) = hasBackgroundLocation(ctx) && isIgnoringBatteryOptimizations(ctx)

    @SuppressLint("BatteryLife") // Pod is distributed outside Google Play; reliable background location needs this.
    fun batteryOptimizationIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    fun appSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
