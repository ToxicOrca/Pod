package app.podfamily.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import app.podfamily.system.DiagLog

/** Location via Android's built-in LocationManager, for phones without Google Play Services. */
class FallbackLocation(context: Context, private val onFix: (Location) -> Unit) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    // Must be a full object, not a SAM lambda: before API 30 the other LocationListener
    // methods are abstract and a lambda crashes with AbstractMethodError when called.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long) {
        stop()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, intervalMs, 25f, listener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                DiagLog.w("Fallback provider $provider failed: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }

    fun stop() {
        runCatching { lm.removeUpdates(listener) }
    }
}
