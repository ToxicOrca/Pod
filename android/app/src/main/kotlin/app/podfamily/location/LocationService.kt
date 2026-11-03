package app.podfamily.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import app.podfamily.data.LocPayload
import app.podfamily.data.PodRepository
import app.podfamily.system.DiagLog
import app.podfamily.system.Notifications
import app.podfamily.system.Permissions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The always-on foreground service. It does two things:
 *  1. collects location fixes and hands them to the repository (adaptive: high
 *     accuracy while moving, low power while still), and
 *  2. runs the sync loop that receives family updates and alerts.
 *
 * Reliability measures: START_STICKY, restart on boot/app update, a WorkManager
 * watchdog, a heartbeat upload every few minutes, and a fallback to Android's
 * own LocationManager on phones without Google Play Services.
 */
class LocationService : LifecycleService() {

    companion object {
        private const val STILL_INTERVAL_MS = 2 * 60_000L
        private const val MOVING_INTERVAL_MS = 30_000L
        private const val DRIVING_SPEED_MPS = 6.7f      // ~15 mph
        private const val DRIVING_INTERVAL_MS = 10_000L // GPS every 10 s while driving
        private const val DRIVING_STOP_MS = 2 * 60_000L // stay in driving mode through red lights
        private const val MOVING_SPEED_MPS = 1.5f        // brisk walk
        private const val STILL_AFTER_MS = 5 * 60_000L   // drop back to low power after 5 min still
        private const val HEARTBEAT_MS = 5 * 60_000L

        @Volatile var isRunning = false
            private set
    }

    private lateinit var repo: PodRepository
    private var syncJob: Job? = null
    private var heartbeatJob: Job? = null
    private var stateJob: Job? = null
    private var lastFix: Location? = null
    /** The last steadied position we reported (see LocationSmoothing). */
    private var lastSteady: LocPayload? = null
    private var moving = false
    private var driving = false
    private var lastDrivingAt = 0L
    private var lastMovingAt = 0L
    private var usingFused = false
    private var fallback: FallbackLocation? = null

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = PodRepository.get(this)
        isRunning = true
        DiagLog.i("Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_SERVICE, Notifications.serviceNotification(this, statusText()),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            DiagLog.e("startForeground failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        // Checked after startForeground: Android 12+ crashes an app whose service was
        // started with startForegroundService() but stops before calling startForeground().
        if (!repo.isInFamily || !Permissions.hasBackgroundLocation(this)) {
            DiagLog.w("Service started without family or permission; stopping")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startLocationUpdates()
        requestFreshFix("service start")
        if (syncJob?.isActive != true) {
            // Off the main thread: decrypting, verifying and saving must never freeze the UI.
            syncJob = lifecycleScope.launch(Dispatchers.Default) { repo.runSync() }
        }
        if (heartbeatJob?.isActive != true) {
            heartbeatJob = lifecycleScope.launch { heartbeat() }
        }
        // Keep the notification text in sync with sharing/paused state.
        if (stateJob?.isActive != true) stateJob = lifecycleScope.launch {
            repo.state.map { Triple(it.sharingEnabled, it.removed, it.family != null) }.distinctUntilChanged().collect { (_, removed, inFamily) ->
                if (removed || !inFamily) {
                    stopSelf()
                } else {
                    updateNotification()
                }
            }
        }
        return START_STICKY
    }

    private fun statusText(): String {
        val st = repo.state.value
        return when {
            !st.sharingEnabled -> "Location sharing is paused"
            else -> "Sharing your location with your family"
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification() {
        if (!Permissions.hasNotifications(this)) return
        NotificationManagerCompat.from(this).notify(Notifications.ID_SERVICE, Notifications.serviceNotification(this, statusText()))
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!Permissions.hasFineOrCoarse(this)) return
        val playOk = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        if (playOk) {
            usingFused = true
            fused.removeLocationUpdates(callback)
            val req = if (driving) {
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, DRIVING_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(5_000)
                    .build()
            } else if (moving) {
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, MOVING_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(15_000)
                    .setMinUpdateDistanceMeters(20f)
                    .build()
            } else {
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, STILL_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(30_000)
                    .setMinUpdateDistanceMeters(30f)
                    .setMaxUpdateDelayMillis(STILL_INTERVAL_MS * 2)
                    .build()
            }
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
                .addOnFailureListener { DiagLog.e("Location request failed: ${it.message}") }
        } else {
            usingFused = false
            if (fallback == null) {
                DiagLog.i("Google Play Services not available - using Android LocationManager")
                fallback = FallbackLocation(this) { onFix(it) }
            }
            fallback?.start(if (driving) DRIVING_INTERVAL_MS else if (moving) MOVING_INTERVAL_MS else STILL_INTERVAL_MS)
        }
    }

    /**
     * One precise fix right away, so a family member's dot appears within seconds
     * instead of waiting for the low-power schedule.
     */
    @SuppressLint("MissingPermission")
    private fun requestFreshFix(reason: String) {
        if (!Permissions.hasFineOrCoarse(this)) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)) {
            DiagLog.w("Location is turned OFF in the phone's settings")
            return
        }
        if (!usingFused) {
            fallback?.lastKnown()?.let { onFix(it, force = true) }
            return
        }
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) onFix(loc, force = true) else DiagLog.w("No location fix available ($reason)")
            }
            .addOnFailureListener { DiagLog.w("Location fix failed ($reason): ${it.message}") }
    }

    private fun onFix(location: Location, force: Boolean = false) {
        val now = System.currentTimeMillis()
        // Low-power fixes often have no speed; derive it from the previous fix.
        val prev = lastFix
        val derived = if (prev != null && location.time > prev.time && location.accuracy < 100 && prev.accuracy < 100) {
            location.distanceTo(prev) / ((location.time - prev.time) / 1000f)
        } else 0f
        if (prev == null) DiagLog.i("First location fix (accuracy ${location.accuracy.toInt()} m)")
        lastFix = location
        val speed = if (location.hasSpeed()) location.speed else derived
        val wasMoving = moving
        if (speed >= MOVING_SPEED_MPS) {
            moving = true
            lastMovingAt = now
        } else if (moving && now - lastMovingAt > STILL_AFTER_MS) {
            moving = false
        }
        val wasDriving = driving
        if (speed >= DRIVING_SPEED_MPS) {
            driving = true
            lastDrivingAt = now
        } else if (driving && now - lastDrivingAt > DRIVING_STOP_MS) {
            driving = false
        }
        if (moving != wasMoving || driving != wasDriving) {
            DiagLog.i(
                when {
                    driving -> "Driving: updates every 10 s"
                    moving -> "Moving: high accuracy mode"
                    else -> "Still: low power mode"
                }
            )
            startLocationUpdates()
        }
        val raw = LocPayload(
            lat = location.latitude,
            lon = location.longitude,
            acc = location.accuracy,
            t = location.time.takeIf { it > 0 } ?: now,
            bat = batteryPercent(),
            chg = isCharging(),
            spd = if (location.hasSpeed()) location.speed else null,
        )
        // Steady a resting phone's position before it's shared or checked against places.
        val payload = LocationSmoothing.steady(lastSteady, raw)
        lastSteady = payload
        lifecycleScope.launch(Dispatchers.Default) {
            // A possible departure: confirm it with a fresh precise fix instead of trusting one reading.
            if (repo.onLocation(payload, forceUpload = force)) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { requestFreshFix("confirm departure") }
            }
        }
    }

    /** Make sure the family sees a fresh "last updated" even when nobody moves. */
    @SuppressLint("MissingPermission")
    private suspend fun heartbeat() {
        while (lifecycleScope.isActive) {
            delay(HEARTBEAT_MS)
            repo.checkStale()
            if (!Permissions.hasFineOrCoarse(this)) continue
            try {
                if (usingFused) {
                    // Low-power readings have been poor (e.g. countryside with little Wi-Fi):
                    // spend a few seconds of GPS on this one check instead.
                    val poor = (lastFix?.accuracy ?: 0f) > 150f
                    fused.getCurrentLocation(if (poor) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                        .addOnSuccessListener { loc -> loc?.let { onFix(it, force = true) } }
                } else {
                    fallback?.lastKnown()?.let { onFix(it, force = true) }
                }
            } catch (e: Exception) {
                DiagLog.w("Heartbeat failed: ${e.message}")
            }
        }
    }

    private fun batteryPercent(): Int? =
        (getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    private fun isCharging(): Boolean? =
        (getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.isCharging

    override fun onDestroy() {
        isRunning = false
        runCatching { fused.removeLocationUpdates(callback) }
        fallback?.stop()
        DiagLog.i("Service destroyed")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away from recents must not stop sharing.
        DiagLog.i("Task removed; service keeps running")
        super.onTaskRemoved(rootIntent)
    }
}
