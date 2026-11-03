package app.podfamily.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val PodJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

// ---------------------------------------------------------------------------
// Stored on the phone (encrypted at rest, see EncryptedFile)
// ---------------------------------------------------------------------------

/** Private material. Never leaves the phone. */
@Serializable
data class Secrets(
    val signSeed: String,           // base64 Ed25519 seed
    val signPub: String,            // base64
    val boxPriv: String,            // base64 X25519 private key
    val boxPub: String,             // base64
    val familyKeys: Map<Long, String> = emptyMap(), // epoch -> base64 key
    /** Copy of the family config, so the app can recover even if the state file can't be read. */
    val family: FamilyConfig? = null,
    /** Admin only: secrets of invites not yet used, keyed by token hash. */
    val pendingInvites: Map<String, PendingInvite> = emptyMap(),
    /**
     * Admin only: devices whose invite proof this phone verified, with the X25519 key the
     * proof covered (deviceId -> base64 boxPub). Family keys are only ever sealed to these
     * pinned keys, never to a key the server reports later.
     */
    val trusted: Map<String, String> = emptyMap(),
    /** Admin only: [trusted] has been initialised (older installs are migrated once). */
    val trustSeeded: Boolean = false,
    /** Admin only: a key rotation that hasn't been accepted by the server yet. Retried until it is. */
    val pendingRotation: PendingRotation? = null,
    /** When each family key epoch became known to this phone (epoch -> epoch millis). */
    val keyAddedAt: Map<Long, Long> = emptyMap(),
)

@Serializable
data class PendingRotation(val epoch: Long, val key: String, val createdAt: Long)

@Serializable
data class PendingInvite(val secret: String, val expiresAt: Long)

@Serializable
data class FamilyConfig(
    val serverUrl: String,
    val familyId: String,
    val deviceId: String,
    val adminId: String,
)

@Serializable
data class Member(
    val deviceId: String,
    val signPub: String,
    val boxPub: String,
    val role: String,
    val joinedAt: Long = 0,
    val name: String? = null,
    val color: Int? = null,
    val location: LocPayload? = null,
    val lastEvent: EventPayload? = null,
    /** Admin: proof verified and family key delivered. Members: true once we've read their profile. */
    val approved: Boolean = false,
    /** Newest key epoch this member has been seen using. Older-epoch messages from them are then refused. */
    val maxEpoch: Long = 0,
    /** The place they're at now (by their latest location), and since when (0 = unknown). */
    val placeId: String? = null,
    val placeSince: Long = 0,
    /** false until we've seen a location for them, so an old visit isn't mistaken for a new arrival. */
    val placeTracked: Boolean = false,
)

@Serializable
data class AppState(
    val family: FamilyConfig? = null,
    val myName: String = "",
    val myColor: Int = MemberColors[0],
    val cursor: Long = 0,
    val currentEpoch: Long = 0,
    val members: Map<String, Member> = emptyMap(),
    val places: List<Place> = emptyList(),
    val placesUpdatedAt: Long = 0,
    val sharingEnabled: Boolean = true,
    val historyDays: Int = 7,
    val insidePlaces: Set<String> = emptySet(),
    val profilePublishedEpoch: Long = 0,
    val removed: Boolean = false,
    /** The guided permission screen was completed (user tapped Continue). */
    val permissionsSetupDone: Boolean = false,
    /** When this phone last saved a recovery kit (0 = never). */
    val recoveryKitSavedAt: Long = 0,
    /** This phone's alert choices per place (place id -> prefs). Local only, never shared. */
    val placeAlerts: Map<String, PlaceAlertPref> = emptyMap(),
    /** Members whose arrive/leave/check-in alerts this phone doesn't want. SOS is never muted. */
    val mutedMembers: Set<String> = emptySet(),

    // --- What this phone shares (each can be turned off in Settings) ---
    /** false = location is rounded to about 1 km before it leaves the phone. */
    val preciseLocation: Boolean = true,
    val shareBattery: Boolean = true,
    val shareCharging: Boolean = true,
    val shareSpeed: Boolean = true,
    val shareArrivals: Boolean = true,

    // --- Display ---
    val mapTheme: String = "midnight",
    val useMetric: Boolean = false,
    val addressSearch: Boolean = true,

    // --- Alerts (all local to this phone) ---
    val lowBatteryAlerts: Boolean = true,
    val quietHours: Boolean = false,
    val quietStartMin: Int = 22 * 60,
    val quietEndMin: Int = 7 * 60,
    val oneTimeAlerts: List<OneTimeAlert> = emptyList(),
    /** Warn when someone's phone hasn't updated for this many hours. 0 = off. */
    val staleAlertHours: Int = 3,
    val staleNotified: Set<String> = emptySet(),

    // --- Delivery bookkeeping ---
    /** Events (arrive/leave/SOS/...) waiting to be sent. Saved so nothing is lost if Android stops the app. */
    val pendingEvents: List<EventPayload> = emptyList(),
    /** Recently handled events ("sender:type:t"), so a replayed or re-delivered event never alerts twice. */
    val seenEvents: List<String> = emptyList(),
    /** Devices this phone learned were removed from the family. Never trusted again. */
    val removedDevices: Set<String> = emptySet(),
    /** The family was told this phone lost location permission (reset once it's back). */
    val permissionLostReported: Boolean = false,
)

@Serializable
data class PlaceAlertPref(
    val arrive: Boolean = true,
    val leave: Boolean = true,
    /** Only alert during a weekly schedule (e.g. school drop-off/pick-up). */
    val scheduled: Boolean = false,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),   // ISO: 1 = Monday ... 7 = Sunday
    val startMin: Int = 7 * 60,
    val endMin: Int = 17 * 60,
)

/** "Tell me once when <member> arrives/leaves <place or anywhere>". Local to this phone. */
@Serializable
data class OneTimeAlert(val id: String, val memberId: String, val type: String, val placeId: String? = null)

/** Minutes since midnight, local time. */
fun minutesNow(): Int {
    val c = java.util.Calendar.getInstance()
    return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
}

/** ISO day of week: 1 = Monday ... 7 = Sunday. */
fun isoDayNow(): Int = (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1

/** True if [now] falls in [start, end), wrapping past midnight when start > end. */
fun inWindow(now: Int, start: Int, end: Int): Boolean =
    if (start <= end) now in start until end else now >= start || now < end

fun formatMinutes(min: Int): String {
    val h = (min / 60) % 24
    val m = min % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "%d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
}

// ---------------------------------------------------------------------------
// Encrypted payloads exchanged between family members (server never sees these)
// ---------------------------------------------------------------------------

@Serializable
data class LocPayload(
    val lat: Double,
    val lon: Double,
    val acc: Float,
    val t: Long,               // fix time, epoch millis
    val bat: Int? = null,      // battery percent
    val chg: Boolean? = null,  // charging
    val spd: Float? = null,    // m/s
)

@Serializable
data class ProfilePayload(val name: String, val color: Int)

@Serializable
data class EventPayload(
    val type: String,          // arrive | leave | sos | checkin | paused | resumed | noperm
    val t: Long,
    val place: String? = null,
    val placeId: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
data class PlacesPayload(val places: List<Place>, val t: Long)

@Serializable
data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radius: Int = 150,     // meters
)

/** Everything needed to bring this phone's identity back on a new phone. Encrypted by RecoveryKit. */
@Serializable
data class RecoveryPayload(
    val v: Int = 1,
    val createdAt: Long,
    val secrets: Secrets,
    val state: AppState,
)

/** The QR code contents for joining a family. */
data class InviteLink(
    val serverUrl: String,
    val familyId: String,
    val token: String,         // base64url, sent to server once
    val secret: String,        // base64url, never sent to server
    val adminId: String,       // pins the admin's identity key
) {
    fun toUri(): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "pod://join?v=1&u=${enc(serverUrl)}&f=$familyId&t=$token&s=$secret&a=$adminId"
    }

    companion object {
        fun parse(text: String): InviteLink? {
            if (!text.startsWith("pod://join?")) return null
            val params = text.substringAfter('?').split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            if (params["v"] != "1") return null
            val u = params["u"] ?: return null
            if (!u.startsWith("https://")) return null
            return InviteLink(
                serverUrl = u.trimEnd('/'),
                familyId = params["f"] ?: return null,
                token = params["t"] ?: return null,
                secret = params["s"] ?: return null,
                adminId = params["a"] ?: return null,
            )
        }
    }
}

/** Distinct, high-contrast colors that read well on the dark map. */
val MemberColors = listOf(
    0xFF5EC8F2.toInt(), // sky
    0xFFF2B35E.toInt(), // amber
    0xFF8BE3C9.toInt(), // mint
    0xFFF27E9B.toInt(), // pink
    0xFFB49BFF.toInt(), // lavender
    0xFFFFE066.toInt(), // yellow
    0xFF7FE07F.toInt(), // green
    0xFFFF8A65.toInt(), // coral
)
