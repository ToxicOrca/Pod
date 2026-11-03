package app.podfamily

import app.podfamily.data.Place
import app.podfamily.location.Geofence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceConfirmTest {
    private val home = Place("home", "Home", 45.52, -122.68, 150)
    private val places = listOf(home)
    private fun lat(metersNorth: Double) = home.lat + metersNorth / 111_320.0
    private val inside = setOf("home")

    @Test fun oneBadReadingNeverFiresLeave() {
        // A confident but wrong reading 600 m away...
        val a = Geofence.evaluateConfirmed(lat(600.0), home.lon, 20f, 0, places, inside, emptyMap())
        assertTrue(a.transitions.isEmpty())
        assertTrue(a.wantsConfirmFix)
        assertEquals(setOf("home"), a.inside)
        // ...then the fresh fix shows them home again: nothing happened.
        val b = Geofence.evaluateConfirmed(lat(10.0), home.lon, 15f, 5_000, places, a.inside, a.pendingExits)
        assertTrue(b.transitions.isEmpty())
        assertTrue(b.pendingExits.isEmpty())
    }

    @Test fun realDepartureIsConfirmedWithTheFirstOutsideTime() {
        val a = Geofence.evaluateConfirmed(lat(400.0), home.lon, 15f, 1_000, places, inside, emptyMap())
        val b = Geofence.evaluateConfirmed(lat(700.0), home.lon, 15f, 20_000, places, a.inside, a.pendingExits)
        assertTrue(b.transitions.isEmpty()) // too soon to be sure
        val c = Geofence.evaluateConfirmed(lat(1200.0), home.lon, 15f, 50_000, places, b.inside, b.pendingExits)
        val leave = c.transitions.single()
        assertFalse(leave.arrived)
        assertEquals(1_000L, leave.t)
        assertTrue(c.inside.isEmpty())
        assertTrue(c.pendingExits.isEmpty())
    }

    @Test fun roughReadingsDontCountTowardLeaving() {
        val a = Geofence.evaluateConfirmed(lat(1500.0), home.lon, 900f, 0, places, inside, emptyMap())
        assertTrue(a.transitions.isEmpty())
        assertTrue(a.pendingExits.isEmpty())
        assertFalse(a.wantsConfirmFix)
    }

    @Test fun arrivalsStayImmediate() {
        val a = Geofence.evaluateConfirmed(lat(20.0), home.lon, 15f, 0, places, emptySet(), emptyMap())
        assertTrue(a.transitions.single().arrived)
        assertEquals(setOf("home"), a.inside)
    }
}
