package app.podfamily.ui.map

import android.graphics.Color as AColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.podfamily.data.Place
import app.podfamily.system.DiagLog
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos
import kotlin.math.sin

/** A loaded map theme. json == null falls back to the server's /map/style.json. */
data class MapStyleSource(val id: String, val json: String?)

data class MapMember(val id: String, val name: String, val color: Int, val lat: Double, val lon: Double, val stale: Boolean)

/** A camera request. [nonce] makes repeated requests for the same target still fire. */
sealed class CameraTarget {
    abstract val nonce: Long
    data class Point(val lat: Double, val lon: Double, override val nonce: Long = System.nanoTime()) : CameraTarget()
    data class FitAll(override val nonce: Long = System.nanoTime()) : CameraTarget()
    /** Move to a point without changing the zoom. */
    data class Center(val lat: Double, val lon: Double, override val nonce: Long = System.nanoTime()) : CameraTarget()
    data class Fit(val points: List<Pair<Double, Double>>, override val nonce: Long = System.nanoTime()) : CameraTarget()
}

/**
 * Where each remembered map was last looking (memory only). The main map comes back to the
 * same view after visiting Settings or Places instead of re-fitting everyone.
 */
private object MapCameraMemory {
    val saved = HashMap<String, org.maplibre.android.camera.CameraPosition>()
}

/** Never zoom in closer than this when fitting people (two phones in one house would otherwise zoom to max). */
private const val FIT_MAX_ZOOM = 15.5

/** Shown if the server's map isn't installed or reachable: members still appear on a plain dark background. */
private const val FALLBACK_STYLE = """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#121417"}}]}"""

@Composable
fun PodMap(
    styleUrl: String,
    /** Resolved theme; null while loading. */
    theme: MapStyleSource?,
    members: List<MapMember>,
    places: List<Place>,
    camera: CameraTarget?,
    /** Location history line to draw (lat, lon), oldest first. */
    trail: List<Pair<Double, Double>> = emptyList(),
    trailColor: Int = 0xFF5EC8F2.toInt(),
    onMemberTap: (String) -> Unit,
    onPlaceTap: (String) -> Unit,
    onLongPress: (Double, Double) -> Unit,
    onMapUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
    /** The user dragged/pinched the map themselves (used to stop following someone). */
    onUserMovedMap: () -> Unit = {},
    /** Change this to reload the map style (e.g. after coming back online). */
    reloadKey: Int = 0,
    /** Place-editing mode: start here, and draw a live circle of [editRadius] at the map's center. */
    editStart: Pair<Double, Double>? = null,
    editRadius: Int = 150,
    onCenterChange: ((Double, Double) -> Unit)? = null,
    /** Keep this map's view across screen changes (e.g. "main"). null = always start fresh. */
    rememberViewKey: String? = null,
    /** Screen-edge padding in px (left, top, right, bottom): the map's "center" is the middle of what's left. */
    mapPaddingPx: IntArray? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // Skip the "fit everyone" opening view when the caller asked for a specific spot.
    val savedView = remember { rememberViewKey?.let { MapCameraMemory.saved[it] } }
    var didInitialFit by remember { mutableStateOf(camera != null || editStart != null || savedView != null) }
    var fellBack by remember { mutableStateOf(false) }
    // Keep the compass below our status pill / buttons instead of under the status bar.
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val compassTopPx = statusTop + with(density) { 80.dp.roundToPx() }
    val compassRightPx = with(density) { 16.dp.roundToPx() }
    // Keep the map credits (the small "i") above the phone's navigation buttons.
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val attribLeftPx = with(density) { 12.dp.roundToPx() }
    val attribBottomPx = navBottom + with(density) { 8.dp.roundToPx() }
    val editRadiusNow by rememberUpdatedState(editRadius)
    // Members standing close together are fanned out around their shared spot so each dot
    // (and name) can be seen and tapped. Recomputed as the map zooms.
    val membersNow by rememberUpdatedState(members)
    val groupPx = with(density) { 26.dp.toPx() }
    val spreadPx = with(density) { 13.dp.toPx() } // dots are 22 dp: neighbours overlap a little
    var lastMembersJson by remember { mutableStateOf("") }
    fun pushMembers(m: MapLibreMap) {
        val s = m.style ?: return
        if (!s.isFullyLoaded) return
        val json = membersGeoJson(spreadMembers(m, membersNow, groupPx, spreadPx))
        if (json == lastMembersJson) return
        lastMembersJson = json
        s.getSourceAs<GeoJsonSource>("members")?.setGeoJson(json)
    }
    val centerChange by rememberUpdatedState(onCenterChange)
    val tap by rememberUpdatedState(onMemberTap)
    val placeTap by rememberUpdatedState(onPlaceTap)
    val longPress by rememberUpdatedState(onLongPress)
    val unavailable by rememberUpdatedState(onMapUnavailable)
    val userMoved by rememberUpdatedState(onUserMovedMap)

    DisposableEffect(lifecycle, mapView) {
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> { mapView.onDestroy(); destroyed = true }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!destroyed) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    // One-time map setup (listeners). Styles are applied separately so themes can change.
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.setAttributionTintColor(AColor.parseColor("#6B7480"))
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.setCompassMargins(0, compassTopPx, compassRightPx, 0)
            mapView.addOnDidFailLoadingMapListener { msg ->
                if (!fellBack) {
                    fellBack = true
                    DiagLog.w("Map style failed to load: $msg")
                    unavailable()
                    m.setStyle(Style.Builder().fromJson(FALLBACK_STYLE)) { s -> setupLayers(s, withLabels = false); style = s }
                }
            }
            m.addOnMapClickListener { p ->
                val features = m.queryRenderedFeatures(m.projection.toScreenLocation(p), "members-dot")
                val id = features.firstOrNull()?.getStringProperty("id")
                if (id != null) {
                    tap(id); true
                } else {
                    val placeId = m.queryRenderedFeatures(m.projection.toScreenLocation(p), "places-fill")
                        .firstOrNull()?.getStringProperty("id")
                    if (placeId != null) { placeTap(placeId); true } else false
                }
            }
            m.addOnMapLongClickListener { p -> longPress(p.latitude, p.longitude); true }
            m.addOnCameraMoveListener { pushMembers(m) }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userMoved()
            }
            m.addOnCameraIdleListener {
                pushMembers(m)
                if (rememberViewKey != null) MapCameraMemory.saved[rememberViewKey] = m.cameraPosition
            }
            if (savedView != null && camera == null && editStart == null) {
                m.moveCamera(CameraUpdateFactory.newCameraPosition(savedView))
            }
            if (editStart != null) {
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(editStart.first, editStart.second), zoomForRadius(editRadius)))
                m.addOnCameraMoveListener {
                    val t = m.cameraPosition.target ?: return@addOnCameraMoveListener
                    m.style?.getSourceAs<GeoJsonSource>("edit")?.setGeoJson(editGeoJson(t.latitude, t.longitude, editRadiusNow))
                    centerChange?.invoke(t.latitude, t.longitude)
                }
                m.addOnCameraIdleListener {
                    val t = m.cameraPosition.target ?: return@addOnCameraIdleListener
                    centerChange?.invoke(t.latitude, t.longitude)
                }
            }
            map = m
        }
    }

    // System-bar sizes are often 0 on the very first frame, so (re)apply the margins
    // whenever they change, not just once at setup.
    LaunchedEffect(map, attribBottomPx, compassTopPx) {
        val m = map ?: return@LaunchedEffect
        m.uiSettings.setAttributionMargins(attribLeftPx, 0, 0, attribBottomPx)
        m.uiSettings.setCompassMargins(0, compassTopPx, compassRightPx, 0)
    }

    LaunchedEffect(map, mapPaddingPx?.toList()) {
        val m = map ?: return@LaunchedEffect
        val p = mapPaddingPx ?: return@LaunchedEffect
        m.moveCamera(CameraUpdateFactory.paddingTo(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble()))
        // Redraw the editing circle at the (unchanged) target in its new on-screen position.
        if (editStart != null) {
            val t = m.cameraPosition.target
            if (t != null) m.style?.getSourceAs<GeoJsonSource>("edit")?.setGeoJson(editGeoJson(t.latitude, t.longitude, editRadiusNow))
        }
    }

    // Apply (or re-apply) the chosen theme. styleJson == null means "use the server's default style".
    LaunchedEffect(map, theme, reloadKey) {
        val m = map ?: return@LaunchedEffect
        val t = theme ?: return@LaunchedEffect
        fellBack = false
        val builder = t.json?.let { Style.Builder().fromJson(it) } ?: Style.Builder().fromUri(styleUrl)
        m.setStyle(builder) { s -> setupLayers(s, withLabels = true); style = s }
    }

    LaunchedEffect(style, members) {
        style ?: return@LaunchedEffect
        lastMembersJson = ""
        map?.let { pushMembers(it) }
        if (!didInitialFit && members.isNotEmpty()) {
            didInitialFit = true
            map?.let { fit(it, members, animate = false) }
        }
    }

    LaunchedEffect(style, trail, trailColor) {
        style?.getSourceAs<GeoJsonSource>("trail")?.setGeoJson(trailGeoJson(trail, trailColor))
    }

    LaunchedEffect(style, places) {
        style?.getSourceAs<GeoJsonSource>("places")?.setGeoJson(placesGeoJson(places))
        style?.getSourceAs<GeoJsonSource>("place-labels")?.setGeoJson(placeLabelsGeoJson(places))
    }

    // Redraw the editing circle whenever the size changes (live while dragging the slider).
    LaunchedEffect(style, editRadius) {
        if (editStart == null) return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        val t = map?.cameraPosition?.target ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("edit")?.setGeoJson(editGeoJson(t.latitude, t.longitude, editRadius))
    }

    LaunchedEffect(map, camera) {
        val m = map ?: return@LaunchedEffect
        when (camera) {
            is CameraTarget.Center -> m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(camera.lat, camera.lon)))
            is CameraTarget.Point -> m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(camera.lat, camera.lon), 15.5))
            is CameraTarget.FitAll -> fit(m, members, animate = true)
            is CameraTarget.Fit -> fit(m, camera.points.map { MapMember("", "", 0, it.first, it.second, false) }, animate = true)
            null -> Unit
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** A zoom level where a circle of this radius fills a good part of a phone screen. */
private fun zoomForRadius(radius: Int): Double =
    (16.6 - Math.log(radius / 100.0) / Math.log(2.0)).coerceIn(12.0, 18.0)

private fun editGeoJson(lat: Double, lon: Double, radius: Int): String =
    placesGeoJson(listOf(Place("edit", "", lat, lon, radius)))

private fun fit(m: MapLibreMap, members: List<MapMember>, animate: Boolean) {
    if (members.isEmpty()) return
    val update = if (members.size == 1) {
        CameraUpdateFactory.newLatLngZoom(LatLng(members[0].lat, members[0].lon), 14.5)
    } else {
        val b = LatLngBounds.Builder().includes(members.map { LatLng(it.lat, it.lon) }).build()
        // Fit everyone, but never closer than street level: people in the same house
        // would otherwise give a tiny box and a maximum zoom.
        val cp = try { m.getCameraForLatLngBounds(b, intArrayOf(140, 140, 140, 140)) } catch (e: Exception) { null }
        if (cp != null && cp.zoom > FIT_MAX_ZOOM) {
            CameraUpdateFactory.newCameraPosition(
                org.maplibre.android.camera.CameraPosition.Builder(cp).zoom(FIT_MAX_ZOOM).build()
            )
        } else {
            CameraUpdateFactory.newLatLngBounds(b, 140)
        }
    }
    try {
        if (animate) m.animateCamera(update) else m.moveCamera(update)
    } catch (e: Exception) {
        // Identical points make a zero-size bounds; just center instead.
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(members[0].lat, members[0].lon), 14.5))
    }
}

private fun setupLayers(s: Style, withLabels: Boolean) {
    s.addSource(GeoJsonSource("places", placesGeoJson(emptyList())))
    s.addSource(GeoJsonSource("members", membersGeoJson(emptyList())))
    s.addSource(GeoJsonSource("trail", trailGeoJson(emptyList(), 0)))
    s.addSource(GeoJsonSource("edit", placesGeoJson(emptyList())))
    s.addSource(GeoJsonSource("place-labels", placeLabelsGeoJson(emptyList())))
    s.addLayer(
        FillLayer("places-fill", "places").withProperties(
            PropertyFactory.fillColor("#5EC8F2"), PropertyFactory.fillOpacity(0.10f),
        )
    )
    s.addLayer(
        LineLayer("places-line", "places").withProperties(
            PropertyFactory.lineColor("#5EC8F2"), PropertyFactory.lineWidth(1.5f), PropertyFactory.lineOpacity(0.6f),
        )
    )
    s.addLayer(
        FillLayer("edit-fill", "edit").withProperties(
            PropertyFactory.fillColor("#F2B45E"), PropertyFactory.fillOpacity(0.18f),
        )
    )
    s.addLayer(
        LineLayer("edit-line", "edit").withProperties(
            PropertyFactory.lineColor("#F2B45E"), PropertyFactory.lineWidth(2.5f),
        )
    )
    s.addLayer(
        LineLayer("trail-line", "trail").withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
        )
    )
    s.addLayer(
        CircleLayer("trail-ends", "trail").withProperties(
            PropertyFactory.circleRadius(5f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleStrokeColor("#0E1013"),
            PropertyFactory.circleStrokeWidth(2f),
        ).withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point")))
    )
    s.addLayer(
        CircleLayer("members-halo", "members").withProperties(
            PropertyFactory.circleRadius(20f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleOpacity(Expression.product(Expression.get("opacity"), Expression.literal(0.25f))),
        )
    )
    s.addLayer(
        CircleLayer("members-dot", "members").withProperties(
            PropertyFactory.circleRadius(11f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleOpacity(Expression.get("opacity")),
            PropertyFactory.circleStrokeColor("#0E1013"),
            PropertyFactory.circleStrokeWidth(3f),
        )
    )
    if (withLabels) {
        // Place names, just above each circle's top edge.
        s.addLayer(
            SymbolLayer("places-label", "place-labels").withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(arrayOf("Noto Sans Medium")),
                PropertyFactory.textSize(12f),
                PropertyFactory.textAnchor(org.maplibre.android.style.layers.Property.TEXT_ANCHOR_BOTTOM),
                PropertyFactory.textColor("#9AD8F2"),
                PropertyFactory.textHaloColor("#0E1013"),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textAllowOverlap(false),
            ).withProperties(PropertyFactory.visibility(org.maplibre.android.style.layers.Property.VISIBLE))
                .also { it.minZoom = 11f }
        )
        s.addLayer(
            SymbolLayer("members-label", "members").withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(arrayOf("Noto Sans Medium")),
                PropertyFactory.textSize(13f),
                PropertyFactory.textOffset(arrayOf(0f, 1.8f)),
                PropertyFactory.textColor("#E8EAED"),
                PropertyFactory.textHaloColor("#0E1013"),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            )
        )
    }
}

private fun colorHex(c: Int) = String.format("#%06X", 0xFFFFFF and c)

private fun membersGeoJson(members: List<MapMember>): String {
    val features = JSONArray()
    for (m in members) {
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(m.lon).put(m.lat)))
                .put(
                    "properties", JSONObject()
                        .put("id", m.id).put("name", m.name).put("color", colorHex(m.color))
                        .put("opacity", if (m.stale) 0.45 else 1.0)
                )
        )
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

/** One label point per place, at the top edge of its circle. */
private fun placeLabelsGeoJson(places: List<Place>): String {
    val features = JSONArray()
    for (p in places) {
        val top = p.lat + p.radius / 111_320.0
        features.put(
            JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(top)))
                .put("properties", JSONObject().put("id", p.id).put("name", p.name))
        )
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

/**
 * Members whose dots would overlap on screen (e.g. everyone at home) are drawn as ONE marker
 * at their real shared spot: the dots sit side by side, slightly overlapping, with a single
 * label such as "You & Sam". The offset is a few pixels, so it stays accurate at every zoom.
 * Their real positions are unchanged everywhere else in the app.
 */
private fun spreadMembers(m: MapLibreMap, members: List<MapMember>, groupPx: Float, stepPx: Float): List<MapMember> {
    if (members.size < 2) return members
    val pts = members.map { m.projection.toScreenLocation(LatLng(it.lat, it.lon)) }
    // Union-find over "closer than groupPx on screen".
    val parent = IntArray(members.size) { it }
    fun find(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
    for (i in members.indices) for (j in i + 1 until members.size) {
        val dx = pts[i].x - pts[j].x
        val dy = pts[i].y - pts[j].y
        if (dx * dx + dy * dy < groupPx * groupPx) parent[find(j)] = find(i)
    }
    val out = members.toMutableList()
    for (group in members.indices.groupBy { find(it) }.values) {
        if (group.size < 2) continue
        val cx = group.map { pts[it].x }.average().toFloat()
        val cy = group.map { pts[it].y }.average().toFloat()
        // "You" first, then alphabetical: stable order, so the dots don't swap places.
        val ordered = group.sortedWith(compareBy<Int> { members[it].name != "You" }.thenBy { members[it].name })
        val names = ordered.map { members[it].name }
        val label = when {
            names.size == 2 -> "${names[0]} & ${names[1]}"
            names.size == 3 -> "${names[0]}, ${names[1]} & ${names[2]}"
            else -> "${names[0]}, ${names[1]} +${names.size - 2}"
        }
        val mid = (ordered.size - 1) / 2f
        ordered.forEachIndexed { k, idx ->
            val x = cx + (k - mid) * stepPx
            val ll = m.projection.fromScreenLocation(android.graphics.PointF(x, cy))
            // One label for the whole group, under the middle dot; the others have none.
            val name = if (k == ordered.size / 2) label else ""
            out[idx] = members[idx].copy(lat = ll.latitude, lon = ll.longitude, name = name)
        }
    }
    return out
}

/** Places are drawn as true-size circles (polygons), so the radius on the map matches the geofence. */
private fun placesGeoJson(places: List<Place>): String {
    val features = JSONArray()
    for (p in places) {
        val ring = JSONArray()
        val latR = Math.toRadians(p.lat)
        for (i in 0..64) {
            val a = 2 * Math.PI * i / 64
            val dLat = p.radius * cos(a) / 111_320.0
            val dLon = p.radius * sin(a) / (111_320.0 * cos(latR))
            ring.put(JSONArray().put(p.lon + dLon).put(p.lat + dLat))
        }
        features.put(
            JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring)))
                .put("properties", JSONObject().put("id", p.id).put("name", p.name))
        )
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

/** The trail as a line plus a dot at the start (oldest point). */
private fun trailGeoJson(points: List<Pair<Double, Double>>, color: Int): String {
    val features = JSONArray()
    if (points.size >= 2) {
        val line = JSONArray()
        points.forEach { line.put(JSONArray().put(it.second).put(it.first)) }
        features.put(
            JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", line))
                .put("properties", JSONObject().put("color", colorHex(color)))
        )
    }
    points.firstOrNull()?.let { p ->
        features.put(
            JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.second).put(p.first)))
                .put("properties", JSONObject().put("color", colorHex(color)))
        )
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}
