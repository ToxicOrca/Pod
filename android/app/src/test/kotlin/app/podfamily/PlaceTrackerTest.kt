package app.podfamily

import app.podfamily.data.EventPayload
import app.podfamily.data.LocPayload
import app.podfamily.data.Place
import app.podfamily.location.PlaceTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceTrackerTest {
    private val home = Place("home", "Home", 45.52, -122.68, 150)
    private val places = listOf(home)
    // ~111 m per 0.001 degree of latitude
    private fun at(metersNorth: Double, t: Long, acc: Float = 20f) =
        LocPayload(home.lat + metersNorth / 111_320.0, home.lon, acc, t)

    private val untracked = PlaceTracker.State(null, 0, tracked = false)

    @Test fun firstFixAfterUpdateHasUnknownTime() {
        val s = PlaceTracker.update(untracked, at(0.0, 1_000), places, null)
        assertEquals("home", s.placeId)
        assertEquals(0L, s.since)
    }

    @Test fun firstFixUsesArrivalEventTime() {
        val arrive = EventPayload("arrive", 500, "Home", "home")
        val s = PlaceTracker.update(untracked, at(0.0, 1_000), places, arrive)
        assertEquals(500L, s.since)
    }

    @Test fun jitterAtTheEdgeDoesNotResetTheClock() {
        var s = PlaceTracker.State("home", 1_000, tracked = true)
        // Fixes wobbling just outside the 150 m circle, but within the 75 m tolerance.
        for ((i, m) in listOf(160.0, 140.0, 200.0, 155.0).withIndex()) {
            s = PlaceTracker.update(s, at(m, 2_000L + i), places, null)
        }
        assertEquals("home", s.placeId)
        assertEquals(1_000L, s.since)
    }

    @Test fun roughFixNeverMovesYouOut() {
        val s = PlaceTracker.update(PlaceTracker.State("home", 1_000, true), at(2_000.0, 5_000, acc = 1500f), places, null)
        assertEquals("home", s.placeId)
        assertEquals(1_000L, s.since)
    }

    @Test fun leavingAndComingBackStartsANewVisit() {
        var s = PlaceTracker.State("home", 1_000, tracked = true)
        s = PlaceTracker.update(s, at(1_000.0, 2_000), places, null)
        assertNull(s.placeId)
        s = PlaceTracker.update(s, at(0.0, 3_000), places, null)
        assertEquals("home", s.placeId)
        assertEquals(3_000L, s.since)
    }

    @Test fun deletedPlaceIsLeft() {
        val s = PlaceTracker.update(PlaceTracker.State("home", 1_000, true), at(0.0, 2_000), emptyList(), null)
        assertNull(s.placeId)
    }
}
