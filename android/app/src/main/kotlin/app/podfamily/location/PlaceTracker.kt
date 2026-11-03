package app.podfamily.location

import app.podfamily.data.EventPayload
import app.podfamily.data.LocPayload
import app.podfamily.data.Place
import kotlin.math.max

/**
 * Tracks which place someone is at and since when ("At Home since 3:12 PM").
 * Pure logic, unit-tested. Rules:
 * - Same tolerance as arrival alerts: once at a place, you only leave it when you're clearly
 *   outside (radius + max(75 m, GPS accuracy)). A very rough fix (> 250 m) never moves you out.
 * - The time comes from their arrival event when we have one, else the first fix inside.
 * - Before the first fix after install/update, the arrival time is unknown (0) unless an
 *   arrival event says when.
 */
object PlaceTracker {
    data class State(val placeId: String?, val since: Long, val tracked: Boolean)

    private const val ROUGH_FIX_M = 250f
    private const val RECENT_ARRIVAL_MS = 30 * 60_000L

    fun update(prev: State, loc: LocPayload, places: List<Place>, lastEvent: EventPayload?): State {
        // Still at the same place? (with tolerance, so GPS jitter at the edge doesn't reset the clock)
        val current = prev.placeId?.let { id -> places.firstOrNull { it.id == id } }
        if (prev.tracked && current != null) {
            val d = Geofence.distanceMeters(loc.lat, loc.lon, current.lat, current.lon)
            if (loc.acc > ROUGH_FIX_M || d <= current.radius + max(75.0, loc.acc.toDouble())) return prev
        }
        val now = if (loc.acc > ROUGH_FIX_M) null else Geofence.placeAt(loc.lat, loc.lon, places)
        val arrival = lastEvent?.takeIf { it.type == "arrive" && now != null && it.placeId == now.id && it.t <= loc.t }
        if (!prev.tracked) {
            return State(now?.id, arrival?.t ?: 0, tracked = true)
        }
        if (now?.id == prev.placeId) return prev
        val recentArrival = arrival?.takeIf { loc.t - it.t < RECENT_ARRIVAL_MS }
        return State(now?.id, if (now == null) 0 else (recentArrival?.t ?: loc.t), tracked = true)
    }
}
