package app.podfamily

import app.podfamily.data.LocPayload
import app.podfamily.location.LocationSmoothing
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationSmoothingTest {
    private val lat0 = 45.52
    private val lon0 = -122.68
    private fun at(metersNorth: Double, tSec: Long, acc: Float, spd: Float? = null) =
        LocPayload(lat0 + metersNorth / 111_320.0, lon0, acc, tSec * 1000, bat = 50, spd = spd)

    @Test fun wobbleWithinUncertaintyKeepsTheBetterSpot() {
        val prev = at(0.0, 0, acc = 30f)
        val next = LocationSmoothing.steady(prev, at(120.0, 120, acc = 150f))
        assertEquals(prev.lat, next.lat, 1e-9)
        assertEquals(120_000L, next.t) // time still refreshed
    }

    @Test fun moreAccurateReadingReplacesTheSpot() {
        val prev = at(0.0, 0, acc = 150f)
        val better = at(60.0, 60, acc = 15f)
        assertEquals(better.lat, LocationSmoothing.steady(prev, better).lat, 1e-9)
    }

    @Test fun impossibleJumpIsIgnored() {
        val prev = at(0.0, 0, acc = 40f)
        val next = LocationSmoothing.steady(prev, at(4_000.0, 30, acc = 500f)) // 4 km in 30 s
        assertEquals(prev.lat, next.lat, 1e-9)
    }

    @Test fun realMoveIsTaken() {
        val prev = at(0.0, 0, acc = 20f)
        val moved = at(800.0, 300, acc = 20f)
        assertEquals(moved.lat, LocationSmoothing.steady(prev, moved).lat, 1e-9)
    }

    @Test fun movingOrLongGapAlwaysTakesTheReading() {
        val prev = at(0.0, 0, acc = 20f)
        val driving = at(50.0, 5, acc = 10f, spd = 15f)
        assertEquals(driving.lat, LocationSmoothing.steady(prev, driving).lat, 1e-9)
        val later = at(100.0, 20 * 60, acc = 200f)
        assertEquals(later.lat, LocationSmoothing.steady(prev, later).lat, 1e-9)
    }
}
