package app.podfamily.location

import app.podfamily.data.Place
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geofences are evaluated on the phone, because the server never sees
 * locations. Hysteresis stops "arrived / left / arrived" flapping when
 * someone sits near the edge of a place or GPS jumps around indoors.
 */
object Geofence {
    /** [t] is when it happened (for confirmed departures: the first reading outside), if known. */
    data class Transition(val place: Place, val arrived: Boolean, val t: Long? = null)

    /** Result of [evaluateConfirmed]. [wantsConfirmFix]: ask for a fresh precise fix now. */
    data class Outcome(
        val inside: Set<String>,
        val transitions: List<Transition>,
        val pendingExits: Map<String, Long>,
        val wantsConfirmFix: Boolean,
    )

    /** A departure must still hold this long after the first reading outside. */
    const val EXIT_CONFIRM_MS = 45_000L
    /** Readings rougher than this never count toward leaving a place. */
    const val EXIT_MAX_ACCURACY_M = 200f

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * @param inside ids of places we are currently inside
     * @return new inside-set and the transitions that happened
     */
    fun evaluate(lat: Double, lon: Double, accuracy: Float, places: List<Place>, inside: Set<String>): Pair<Set<String>, List<Transition>> {
        val result = inside.toMutableSet()
        val transitions = mutableListOf<Transition>()
        for (p in places) {
            val d = distanceMeters(lat, lon, p.lat, p.lon)
            val isInside = p.id in inside
            // Enter: clearly within the radius with a reasonably good fix.
            if (!isInside && d <= p.radius && accuracy <= max(p.radius * 1.5f, 100f)) {
                result += p.id
                transitions += Transition(p, arrived = true)
            }
            // Exit: clearly outside, with a margin for GPS error.
            else if (isInside && d > p.radius + max(75.0, accuracy.toDouble())) {
                result -= p.id
                transitions += Transition(p, arrived = false)
            }
        }
        // Forget places that were deleted.
        result.retainAll(places.map { it.id }.toSet())
        return result to transitions
    }

    /**
     * Like [evaluate], but a departure is only reported once it's confirmed: the first reading
     * clearly outside starts a pending exit (and asks for a fresh precise fix); it becomes a real
     * "left" only if a reading at least [EXIT_CONFIRM_MS] later is still clearly outside. A
     * reading back inside cancels it. One bad reading (e.g. Wi-Fi positioning placing the phone
     * on the wrong street with a confident accuracy) therefore never fires a false "left" alert.
     * Arrivals stay immediate.
     */
    fun evaluateConfirmed(
        lat: Double, lon: Double, accuracy: Float, t: Long,
        places: List<Place>, inside: Set<String>, pending: Map<String, Long>,
    ): Outcome {
        val result = inside.toMutableSet()
        val nextPending = pending.toMutableMap()
        val transitions = mutableListOf<Transition>()
        var wantsFix = false
        for (p in places) {
            val d = distanceMeters(lat, lon, p.lat, p.lon)
            if (p.id !in inside) {
                nextPending -= p.id
                if (d <= p.radius && accuracy <= max(p.radius * 1.5f, 100f)) {
                    result += p.id
                    transitions += Transition(p, arrived = true, t = t)
                }
                continue
            }
            val clearlyOut = d > p.radius + max(75.0, accuracy.toDouble())
            when {
                !clearlyOut -> nextPending -= p.id                 // still there: cancel any pending exit
                accuracy > EXIT_MAX_ACCURACY_M -> Unit              // too rough to judge: wait
                p.id !in nextPending -> {                           // first sign of leaving
                    nextPending[p.id] = t
                    wantsFix = true
                }
                t - nextPending.getValue(p.id) >= EXIT_CONFIRM_MS -> {  // still out later: real
                    result -= p.id
                    transitions += Transition(p, arrived = false, t = nextPending.getValue(p.id))
                    nextPending -= p.id
                }
            }
        }
        val ids = places.map { it.id }.toSet()
        result.retainAll(ids)
        nextPending.keys.retainAll(ids)
        return Outcome(result, transitions, nextPending, wantsFix)
    }

    /** Which place (if any) a point is in - used for "At Home" labels. */
    fun placeAt(lat: Double, lon: Double, places: List<Place>): Place? =
        places.filter { distanceMeters(lat, lon, it.lat, it.lon) <= it.radius }
            .minByOrNull { distanceMeters(lat, lon, it.lat, it.lon) }
}
