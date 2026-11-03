package app.podfamily

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.podfamily.data.AppState
import app.podfamily.data.EventPayload
import app.podfamily.data.FamilyConfig
import app.podfamily.data.LocPayload
import app.podfamily.data.Member
import app.podfamily.data.MemberColors
import app.podfamily.data.OneTimeAlert
import app.podfamily.data.Place
import app.podfamily.data.PlaceAlertPref
import app.podfamily.data.PodRepository

/**
 * Debug builds only: fills the app with a made-up family for README screenshots.
 * Nothing is saved or sent; restart the app to leave the demo.
 *
 *   adb shell am start -n app.podfamily.location.debug/app.podfamily.DemoActivity --es server http://10.0.2.2:8080
 *
 * The server only serves the map (any Pod server with a map installed works, e.g. one run
 * locally with docker). All names and places below are invented.
 */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val server = intent.getStringExtra("server") ?: "http://10.0.2.2:8080"
        val (state, trails) = demoFamily(server, System.currentTimeMillis())
        PodRepository.get(this).startDemo(state, trails)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}

private const val MIN = 60_000L

private fun demoFamily(server: String, now: Long): Pair<AppState, Map<String, List<LocPayload>>> {
    // Portland, Oregon: a neutral demo city.
    val home = Place("p-home", "Home", 45.52640, -122.62790, 120)
    val school = Place("p-school", "School", 45.51530, -122.64130, 150)
    val work = Place("p-work", "Work", 45.51960, -122.67640, 120)
    val grandma = Place("p-grandma", "Grandma's", 45.53480, -122.65120, 100)
    val soccer = Place("p-soccer", "Soccer practice", 45.51700, -122.64870, 110)
    val places = listOf(home, school, work, grandma, soccer)

    fun member(id: String, name: String, color: Int, loc: LocPayload, place: Place?, since: Long, role: String = "member") = Member(
        deviceId = id, signPub = "", boxPub = "", role = role, joinedAt = now - 40 * 24 * 60 * MIN,
        name = name, color = MemberColors[color], location = loc, approved = true, maxEpoch = 1,
        placeId = place?.id, placeSince = since, placeTracked = true,
    )

    // Sam is driving home from work: north out of downtown, then east over the Burnside Bridge.
    val route = listOf(45.51960 to -122.67640, 45.52300 to -122.67640, 45.52300 to -122.64800)
    val samTrail = (0..30).map { i ->
        val f = i / 30.0 * (route.size - 1)
        val seg = minOf(f.toInt(), route.size - 2)
        val (a, b) = route[seg] to route[seg + 1]
        val k = f - seg
        LocPayload(a.first + (b.first - a.first) * k, a.second + (b.second - a.second) * k, 8f, now - (30 - i) * 30_000L, 58, false, 13.4f)
    }
    val members = listOf(
        member("me", "Alex", 0, LocPayload(45.52648, -122.62770, 12f, now - 1 * MIN, 82, true, 0f), home, now - 134 * MIN, role = "admin"),
        member("d-sam", "Sam", 1, samTrail.last(), null, 0),
        member("d-emma", "Emma", 3, LocPayload(45.51690, -122.64880, 15f, now - 3 * MIN, 41, false, 0f), soccer, now - 67 * MIN)
            .copy(lastEvent = EventPayload("arrive", now - 67 * MIN, soccer.name, soccer.id)),
        member("d-leo", "Leo", 2, LocPayload(45.53470, -122.65100, 18f, now - 6 * MIN, 12, false, 0f), grandma, now - 52 * MIN),
    ).associateBy { it.deviceId }

    val state = AppState(
        family = FamilyConfig(server, "demo-family", "me", "me"),
        myName = "Alex",
        myColor = MemberColors[0],
        currentEpoch = 1,
        members = members,
        places = places,
        placesUpdatedAt = now - 3 * 24 * 60 * MIN,
        insidePlaces = setOf(home.id),
        profilePublishedEpoch = 1,
        permissionsSetupDone = true,
        recoveryKitSavedAt = now - 20 * 24 * 60 * MIN,
        placeAlerts = mapOf(school.id to PlaceAlertPref(arrive = true, leave = true, scheduled = true, startMin = 7 * 60 + 30, endMin = 16 * 60)),
        oneTimeAlerts = listOf(OneTimeAlert("a-1", "d-sam", "arrive", home.id)),
        quietHours = true,
    )
    return state to mapOf("d-sam" to samTrail)
}
