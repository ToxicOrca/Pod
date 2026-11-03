package app.podfamily.data

import app.podfamily.BuildConfig
import app.podfamily.location.Geofence
import app.podfamily.net.Http
import app.podfamily.system.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Place / address search for adding places, using OpenStreetMap data:
 *  1. Photon (photon.komoot.io): fast, typo-tolerant, ranks results near you.
 *     Good for businesses ("Burger King") and partial addresses.
 *  2. Nominatim (nominatim.openstreetmap.org): fallback, limited to your area first.
 *  House addresses ("123 Main St, Portland OR") go to the US Census Bureau geocoder first
 *  (geocoding.geo.census.gov). OpenStreetMap is missing many US house numbers; the
 *  Census address database covers almost all of them. Only the typed text is sent there.
 *
 * Privacy: only the typed text and a rough area (rounded to ~11 km) are sent.
 * Never anyone's live location. Can be turned off in Settings.
 */
object AddressSearch {
    data class Result(val title: String, val subtitle: String, val lat: Double, val lon: Double, val distanceMeters: Double? = null)

    private val userAgent = "PodFamilyLocation/${BuildConfig.VERSION_NAME} (self-hosted family app)"

    suspend fun search(query: String, near: LocPayload?): List<Result> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        // Rough area only (0.1 degree is about 11 km).
        val lat = near?.let { Math.round(it.lat * 10) / 10.0 }
        val lon = near?.let { Math.round(it.lon * 10) / 10.0 }

        val looksLikeAddress = Regex("^\\s*\\d+[A-Za-z]?\\s+\\S").containsMatchIn(q)
        val census = if (looksLikeAddress) {
            try { census(q) } catch (e: Exception) { DiagLog.w("Census search failed: ${e.javaClass.simpleName}"); emptyList() }
        } else emptyList()
        var results = census + try {
            photon(q, lat, lon)
        } catch (e: Exception) {
            DiagLog.w("Photon search failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        if (results.isEmpty()) {
            results = nominatim(q, lat, lon, bounded = lat != null)
            if (results.isEmpty() && lat != null) results = nominatim(q, lat, lon, bounded = false)
        }
        // Many US houses aren't numbered in OpenStreetMap yet. If "123 Main St" finds
        // nothing, find the street itself so the user can still get close.
        if (results.isEmpty()) {
            val street = q.replaceFirst(Regex("^\\s*\\d+[A-Za-z]?\\s+"), "")
            if (street != q && street.isNotBlank()) {
                results = (try { photon(street, lat, lon) } catch (e: Exception) { emptyList() })
                    .ifEmpty { nominatim(street, lat, lon, bounded = lat != null) }
                    .map { it.copy(subtitle = listOf("Street only, house number not on the map", it.subtitle).filter { s -> s.isNotBlank() }.joinToString(" · ")) }
            }
        }
        // Distance from the user's real position is computed on the phone only.
        if (near != null) {
            results = results.map { it.copy(distanceMeters = Geofence.distanceMeters(near.lat, near.lon, it.lat, it.lon)) }
        }
        results
    }

    private fun get(url: HttpUrl): String {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept-Language", "en").build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Search failed (HTTP ${r.code})")
            return r.body?.string().orEmpty()
        }
    }

    private fun census(q: String): List<Result> {
        val url = HttpUrl.Builder().scheme("https").host("geocoding.geo.census.gov")
            .addPathSegments("geocoder/locations/onelineaddress")
            .addQueryParameter("address", q).addQueryParameter("benchmark", "Public_AR_Current")
            .addQueryParameter("format", "json").build()
        val matches = JSONObject(get(url)).optJSONObject("result")?.optJSONArray("addressMatches") ?: return emptyList()
        return (0 until matches.length()).map { i ->
            val m = matches.getJSONObject(i)
            val c = m.getJSONObject("coordinates")
            val parts = m.getString("matchedAddress").split(", ").map { titleCase(it) }
            Result(parts.first(), parts.drop(1).joinToString(", "), c.getDouble("y"), c.getDouble("x"))
        }
    }

    // "123 MAIN ST" -> "123 Main St"; keeps state codes like "OR" upper-case.
    private fun titleCase(s: String) = s.split(" ").joinToString(" ") { w ->
        if (w.length == 2 && w.all { it.isLetter() } && w.uppercase() == w && w !in setOf("ST", "DR", "LN", "CT", "RD", "WY")) w
        else w.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun photon(q: String, lat: Double?, lon: Double?): List<Result> {
        val url = HttpUrl.Builder().scheme("https").host("photon.komoot.io").addPathSegments("api/")
            .addQueryParameter("q", q).addQueryParameter("limit", "10").addQueryParameter("lang", "en")
        if (lat != null && lon != null) {
            url.addQueryParameter("lat", lat.toString()).addQueryParameter("lon", lon.toString())
            url.addQueryParameter("zoom", "12").addQueryParameter("location_bias_scale", "0.1")
        }
        val features = JSONObject(get(url.build())).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            val street = listOf(p.optString("housenumber"), p.optString("street")).filter { it.isNotBlank() }.joinToString(" ")
            val name = p.optString("name")
            val title = name.ifBlank { street }.ifBlank { return@mapNotNull null }
            val subtitle = listOf(if (name.isNotBlank()) street else "", p.optString("city"), p.optString("state"))
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            Result(title, subtitle, c.getDouble(1), c.getDouble(0))
        }
    }

    private fun nominatim(q: String, lat: Double?, lon: Double?, bounded: Boolean): List<Result> {
        val url = HttpUrl.Builder().scheme("https").host("nominatim.openstreetmap.org").addPathSegment("search")
            .addQueryParameter("q", q).addQueryParameter("format", "jsonv2").addQueryParameter("limit", "10")
        if (lat != null && lon != null) {
            url.addQueryParameter("viewbox", "${lon - 0.7},${lat + 0.7},${lon + 0.7},${lat - 0.7}")
            if (bounded) url.addQueryParameter("bounded", "1")
        }
        val arr = JSONArray(get(url.build()))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val parts = o.getString("display_name").split(", ")
            Result(
                title = o.optString("name").ifBlank { parts.first() },
                subtitle = parts.drop(1).take(3).joinToString(", "),
                lat = o.getString("lat").toDouble(),
                lon = o.getString("lon").toDouble(),
            )
        }
    }
}
