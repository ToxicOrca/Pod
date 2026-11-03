package app.podfamily.location

import app.podfamily.data.LocPayload

/**
 * Keeps a resting phone's dot steady. Low-power location (Wi-Fi/cell, used while still to save
 * battery) wobbles by tens to hundreds of meters, and the odd reading lands kilometers away.
 * Rules, for a new reading compared with the previous steady one:
 * - Moving (reported speed >= 1 m/s) or a long gap (> 15 min): take the new reading as-is.
 * - Impossible jump (would need > 50 m/s, ~110 mph): keep the old spot, refresh the time.
 * - Within the combined uncertainty of both readings: it's the same spot. Keep whichever
 *   reading is more accurate, refresh the time and battery.
 * - Otherwise it's a real move: take the new reading.
 * Used on the sending phone (before upload and geofencing) and on receiving phones (so an
 * older app version on someone else's phone still looks steady).
 */
object LocationSmoothing {
    private const val LONG_GAP_MS = 15 * 60_000L
    private const val MOVING_MPS = 1.0f
    private const val MAX_PLAUSIBLE_MPS = 50.0

    fun steady(prev: LocPayload?, new: LocPayload): LocPayload {
        if (prev == null) return new
        val dt = new.t - prev.t
        if (dt <= 0) return new
        if (dt > LONG_GAP_MS) return new
        if ((new.spd ?: 0f) >= MOVING_MPS) return new
        val d = Geofence.distanceMeters(prev.lat, prev.lon, new.lat, new.lon)
        val keepOldSpot = new.copy(lat = prev.lat, lon = prev.lon, acc = prev.acc)
        if (d / (dt / 1000.0) > MAX_PLAUSIBLE_MPS && d > new.acc + prev.acc) return keepOldSpot
        if (d <= new.acc + prev.acc) return if (new.acc < prev.acc) new else keepOldSpot
        return new
    }
}
