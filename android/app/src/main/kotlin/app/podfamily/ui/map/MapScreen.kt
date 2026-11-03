package app.podfamily.ui.map

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.layout.windowInsetsBottomHeight

import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.produceState
import app.podfamily.data.LocPayload
import app.podfamily.data.OneTimeAlert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podfamily.data.Member
import app.podfamily.data.Place
import app.podfamily.data.PlaceAlertPref
import app.podfamily.ui.places.EditPlaceDialog
import app.podfamily.data.PodRepository
import app.podfamily.location.Geofence
import app.podfamily.ui.components.Avatar
import app.podfamily.ui.components.Banner
import app.podfamily.ui.components.StatusDot
import app.podfamily.ui.components.timeAgo
import app.podfamily.ui.theme.PodColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private const val STALE_MS = 30 * 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onPlaces: () -> Unit,
    onInvite: () -> Unit,
    onSettings: () -> Unit,
    /** Open the map zoomed to this place (tapped in the Places list). */
    focusPlace: Place? = null,
    /** Open the map on this person (a tapped notification about them). */
    focusMemberId: String? = null,
    onFocusMemberDone: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val repo = remember { PodRepository.get(ctx) }
    val state by repo.state.collectAsStateWithLifecycle()
    val conn by repo.conn.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var camera by remember { mutableStateOf<CameraTarget?>(focusPlace?.let { placeTarget(it) }) }
    /** Why the map couldn't load: "offline" (no connection) or "missing" (not set up on the server). */
    var mapIssue by remember { mutableStateOf<String?>(null) }
    /** Bumped to reload the map style (e.g. when the connection comes back). */
    var mapReload by remember { mutableIntStateOf(0) }
    var newPlaceAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var editingPlaceId by remember { mutableStateOf<String?>(null) }
    /** Tapped a place on the map: show who's there (editing is one more tap away). */
    var placeInfoId by remember { mutableStateOf<String?>(null) }
    /** Tapped a person: the map keeps them centered as they move (e.g. driving) until you pan or stop it. */
    var followId by remember { mutableStateOf<String?>(null) }
    var followFromT by remember { mutableStateOf(0L) }
    var followLastMovingT by remember { mutableStateOf(0L) }
    fun focusMember(id: String) {
        val loc = state.members[id]?.location ?: return
        camera = CameraTarget.Point(loc.lat, loc.lon)
        // Only follow someone who is driving right now; anyone else just gets centered once.
        if (isDriving(loc, System.currentTimeMillis())) {
            followId = id
            followFromT = loc.t
            followLastMovingT = loc.t
        } else {
            followId = null
        }
    }
    LaunchedEffect(focusMemberId) {
        val id = focusMemberId ?: return@LaunchedEffect
        focusMember(id) // also follows them if they're driving
        onFocusMemberDone()
    }
    val followedLoc = followId?.let { state.members[it]?.location }
    LaunchedEffect(followId, followedLoc?.t) {
        val loc = followedLoc ?: return@LaunchedEffect
        if ((loc.spd ?: 0f) >= 1.0f) followLastMovingT = loc.t
        // Parked: stop following once they've been stopped for 3 minutes (red lights don't count).
        if (loc.t - followLastMovingT > 3 * 60_000L) {
            followId = null
            return@LaunchedEffect
        }
        // Each new position from them: glide there, keeping the zoom you chose.
        if (loc.t > followFromT) camera = CameraTarget.Center(loc.lat, loc.lon)
    }
    var actionsFor by remember { mutableStateOf<String?>(null) }
    var trailView by remember { mutableStateOf<TrailView?>(null) }
    var confirmSos by remember { mutableStateOf(false) }
    /** Set when an SOS could not be delivered: shown until dismissed, never a brief snackbar. */
    var sosFailed by remember { mutableStateOf<String?>(null) }
    var sosSending by remember { mutableStateOf(false) }
    fun sendSos() {
        sosSending = true
        // Ask for a fresh GPS fix right away; it's uploaded as soon as it arrives.
        app.podfamily.system.ServiceController.ensureRunning(ctx, "sos", force = true)
        scope.launch {
            repo.sendSos()
                .onSuccess { sosFailed = null; snackbar.showSnackbar("SOS sent to your family") }
                .onFailure { sosFailed = it.message ?: "Couldn't reach the server" }
            sosSending = false
        }
    }
    // Re-render "x min ago" labels every 30s.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }

    val family = state.family ?: return
    val myId = family.deviceId
    val waitingForKey = state.profilePublishedEpoch == 0L
    val now = remember(tick, state) { System.currentTimeMillis() }
    // Back online after the map failed to load: try again automatically.
    // Keyed on the connection only, so a server error can't cause a reload loop.
    LaunchedEffect(conn.online) {
        if (conn.online && mapIssue == "offline") {
            mapIssue = null
            mapReload++
        }
    }
    val mapStyle by produceState<MapStyleSource?>(null, state.mapTheme, family.serverUrl, mapReload) {
        value = MapStyleSource(state.mapTheme, MapThemes.load(family.serverUrl, state.mapTheme))
    }

    // Members that have told us who they are (have a profile), me first.
    val members = state.members.values
        .filter { it.name != null || it.deviceId == myId }
        .sortedWith(compareBy<Member> { it.deviceId != myId }.thenBy { it.name ?: "" })
    val mapMembers = members.mapNotNull { m ->
        val loc = m.location ?: return@mapNotNull null
        MapMember(
            m.deviceId, if (m.deviceId == myId) "You" else (m.name ?: "?"),
            (if (m.deviceId == myId) state.myColor else m.color) ?: 0xFF5EC8F2.toInt(),
            loc.lat, loc.lon, stale = now - loc.t > STALE_MS,
        )
    }

    val sheetState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = false)
    )

    // Peek = drag handle + measured header and first member row + the navigation bar,
    // so the first row ends exactly where the phone's navigation buttons begin.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var peekContentPx by remember { mutableIntStateOf(0) }
    val peekContent = if (peekContentPx > 0) with(density) { peekContentPx.toDp() } else 120.dp
    Box(Modifier.fillMaxSize()) {
    BottomSheetScaffold(
        scaffoldState = sheetState,
        sheetPeekHeight = 48.dp + peekContent + navBarHeight,
        sheetContainerColor = PodColors.Surface,
        containerColor = PodColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        sheetContent = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              // Measured: everything that must be fully visible in the peeked sheet,
              // including Check in and SOS, so they're never below the fold.
              Column(
                  Modifier.fillMaxWidth().onSizeChanged { peekContentPx = it.height + with(density) { 6.dp.roundToPx() } },
                  verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Family", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { followId = null; camera = CameraTarget.FitAll() }) { Icon(Icons.Filled.CenterFocusStrong, "Show everyone") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { scope.launch { repo.checkIn().onSuccess { snackbar.showSnackbar("Checked in") }.onFailure { snackbar.showSnackbar(it.message ?: "Failed") } } },
                        modifier = Modifier.weight(1f).height(50.dp), enabled = !waitingForKey,
                    ) { Text("Check in") }
                    FilledTonalButton(
                        onClick = { confirmSos = true },
                        modifier = Modifier.weight(1f).height(50.dp), enabled = !waitingForKey && !sosSending,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF3A1416), contentColor = Color(0xFFFFB3B5)),
                    ) { Text(if (sosSending) "Sending..." else "SOS", fontWeight = FontWeight.Bold) }
                }
                members.firstOrNull()?.let { m ->
                    MemberRow(
                        m, isMe = m.deviceId == myId, myColor = state.myColor, places = state.places, now = now, metric = state.useMetric,
                        onClick = { focusMember(m.deviceId) },
                        onMore = { actionsFor = m.deviceId },
                    )
                }
              }
                for (m in members.drop(1)) {
                    MemberRow(
                        m, isMe = m.deviceId == myId, myColor = state.myColor, places = state.places, now = now, metric = state.useMetric,
                        onClick = { focusMember(m.deviceId) },
                        onMore = { actionsFor = m.deviceId },
                    )
                }
                if (members.size <= 1 && repo.isAdmin) {
                    Text("Nobody else here yet. Tap the invite button to add your family.", color = PodColors.TextDim, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            PodMap(
                rememberViewKey = "main",
                styleUrl = "${family.serverUrl}/map/style.json",
                theme = mapStyle,
                trail = trailView?.points?.map { it.lat to it.lon } ?: emptyList(),
                trailColor = trailView?.color ?: 0xFF5EC8F2.toInt(),
                members = mapMembers,
                places = state.places,
                camera = camera,
                onMemberTap = { id -> focusMember(id) },
                onUserMovedMap = { followId = null },
                onPlaceTap = { id -> placeInfoId = id },
                onLongPress = { lat, lon -> newPlaceAt = lat to lon },
                onMapUnavailable = {
                    scope.launch { mapIssue = probeMap(family.serverUrl) }
                },
                reloadKey = mapReload,
                modifier = Modifier.fillMaxSize(),
            )
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        when {
                            !state.sharingEnabled -> "Sharing paused" to PodColors.Warn
                            conn.online -> "Connected" to PodColors.Good
                            conn.error != null -> "Reconnecting..." to PodColors.Warn
                            else -> "Connecting..." to PodColors.TextDim
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    RoundIcon(Icons.Filled.Place, "Places", onPlaces)
                    if (repo.isAdmin) { Spacer(Modifier.width(8.dp)); RoundIcon(Icons.Filled.PersonAdd, "Invite", onInvite) }
                    Spacer(Modifier.width(8.dp))
                    RoundIcon(Icons.Filled.Settings, "Settings", onSettings)
                }
                app.podfamily.ui.UpdateBanner()
                followId?.let { id ->
                    val who = if (id == myId) "you" else (state.members[id]?.name ?: "them")
                    FilledTonalButton(onClick = { followId = null }) { Text("Following $who while driving · tap to stop") }
                }
                if (repo.isAdmin && state.recoveryKitSavedAt == 0L && !waitingForKey) {
                    Box(Modifier.clickable(onClick = onSettings)) {
                        Banner("Save a recovery kit so losing this phone never means rebuilding your family. Tap to open Settings.", PodColors.Warn)
                    }
                }
                if (state.pendingEvents.any { it.type == "sos" } && !sosSending) {
                    Banner("Your SOS hasn't reached your family yet. Pod keeps trying. If you need help now, call directly.", PodColors.Danger)
                }
                if (waitingForKey) Banner("Waiting for the family admin's phone to approve this phone. Keep Pod open on both phones for a moment.", PodColors.Accent)
                if (!state.sharingEnabled) Banner("Your family can't see where you are. Turn sharing back on in Settings.", PodColors.Warn)
                trailView?.let { t ->
                    Row(
                        Modifier.fillMaxWidth().background(PodColors.Surface.copy(alpha = 0.95f), RoundedCornerShape(14.dp))
                            .padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(Color(t.color))
                        Spacer(Modifier.width(10.dp))
                        Text("${t.label} last ${hoursLabel(t.hours)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { trailView = null }) { Icon(Icons.Filled.Close, "Hide history") }
                    }
                }
                when (mapIssue) {
                    "offline" -> Banner("No connection right now. The map will load by itself when you're back online.", PodColors.TextDim)
                    "missing" -> Banner("The map isn't set up on the server yet. Family members still show as dots.", PodColors.TextDim)
                }
            }
            // Swiped the family list away? Bring it back.
            if (sheetState.bottomSheetState.currentValue == SheetValue.Hidden) {
                FilledTonalButton(
                    onClick = { scope.launch { sheetState.bottomSheetState.partialExpand() } },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
                ) { Text("Show family") }
            }
        }
    }
    // Opaque strip behind the navigation buttons while the sheet is up, so list
    // content scrolled underneath never shows through the buttons.
    if (sheetState.bottomSheetState.targetValue != SheetValue.Hidden) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars).background(PodColors.Surface)
        )
    }
    }

    fun showTrail(id: String, hours: Int) {
        val mem = state.members[id] ?: return
        val label = if (id == myId) "Your" else "${mem.name ?: "?"}'s"
        val color = (if (id == myId) state.myColor else mem.color) ?: 0xFF5EC8F2.toInt()
        scope.launch {
            repo.loadTrail(id, hours)
                .onSuccess { pts ->
                    if (pts.size < 2) {
                        snackbar.showSnackbar("No location history for that time yet")
                    } else {
                        trailView = TrailView(id, label, hours, pts, color)
                        camera = CameraTarget.Fit(pts.map { it.lat to it.lon })
                    }
                }
                .onFailure { snackbar.showSnackbar(it.message ?: "Couldn't load history") }
        }
    }

    actionsFor?.let { id -> state.members[id] }?.let { mem ->
        MemberActionsDialog(
            member = mem,
            isMe = mem.deviceId == myId,
            places = state.places,
            oneTime = state.oneTimeAlerts.filter { it.memberId == mem.deviceId },
            muted = mem.deviceId in state.mutedMembers,
            onTrail = { h -> actionsFor = null; showTrail(mem.deviceId, h) },
            onAddOneTime = { type, placeId ->
                repo.updateSettings {
                    it.copy(oneTimeAlerts = it.oneTimeAlerts + OneTimeAlert(UUID.randomUUID().toString(), mem.deviceId, type, placeId))
                }
            },
            onRemoveOneTime = { aid -> repo.updateSettings { it.copy(oneTimeAlerts = it.oneTimeAlerts.filter { a -> a.id != aid }) } },
            onMute = { m -> repo.setMemberMuted(mem.deviceId, m) },
            onDismiss = { actionsFor = null },
        )
    }

    placeInfoId?.let { id -> state.places.firstOrNull { it.id == id } }?.let { p ->
        val here = members.filter { m ->
            val loc = m.location ?: return@filter false
            Geofence.distanceMeters(loc.lat, loc.lon, p.lat, p.lon) <= p.radius
        }
        AlertDialog(
            onDismissRequest = { placeInfoId = null },
            containerColor = PodColors.SurfaceHigh,
            title = { Text(p.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (here.isEmpty()) {
                        Text("Nobody is here right now.", color = PodColors.TextDim)
                    } else {
                        for (m in here) {
                            val isMe = m.deviceId == myId
                            val name = if (isMe) "You" else (m.name ?: "?")
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    placeInfoId = null
                                    focusMember(m.deviceId)
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(name, if (isMe) state.myColor else m.color, size = 32.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.bodyLarge)
                                    m.location?.let {
                                        Text("Updated ${timeAgo(it.t)}", style = MaterialTheme.typography.bodySmall, color = PodColors.TextDim)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { placeInfoId = null }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = { placeInfoId = null; editingPlaceId = p.id }) { Text("Edit place") }
            },
        )
    }

    editingPlaceId?.let { id -> state.places.firstOrNull { it.id == id } }?.let { p ->
        EditPlaceDialog(
            place = p,
            pref = state.placeAlerts[p.id] ?: PlaceAlertPref(),
            metric = state.useMetric,
            onDismiss = { editingPlaceId = null },
            onSave = { updated, pref ->
                editingPlaceId = null
                repo.setPlaceAlerts(updated.id, pref)
                if (updated != p) scope.launch {
                    repo.updatePlace(updated).onFailure { snackbar.showSnackbar(it.message ?: "Could not save place") }
                }
            },
            onDelete = {
                editingPlaceId = null
                scope.launch { repo.deletePlace(p.id).onFailure { snackbar.showSnackbar(it.message ?: "Could not delete") } }
            },
        )
    }

    newPlaceAt?.let { (lat, lon) ->
        AddPlaceDialog(
            lat = lat, lon = lon,
            onDismiss = { newPlaceAt = null },
            onSave = { name, plat, plon, radius ->
                newPlaceAt = null
                scope.launch {
                    repo.savePlaces(state.places + Place(UUID.randomUUID().toString(), name, plat, plon, radius))
                        .onSuccess { snackbar.showSnackbar("Added $name") }
                        .onFailure { snackbar.showSnackbar(it.message ?: "Could not save place") }
                }
            },
        )
    }

    if (confirmSos) {
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            containerColor = PodColors.SurfaceHigh,
            title = { Text("Send SOS?") },
            text = { Text("Everyone in your family gets an urgent alert with your location.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSos = false
                    sendSos()
                }) { Text("Send SOS", color = PodColors.Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmSos = false }) { Text("Cancel") } },
        )
    }

    sosFailed?.let { reason ->
        AlertDialog(
            onDismissRequest = { sosFailed = null },
            containerColor = PodColors.SurfaceHigh,
            title = { Text("SOS NOT sent yet", color = PodColors.Danger, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Your family hasn't received it ($reason). Pod will keep trying automatically, " +
                        "but if you need help now, call for help directly.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sosFailed = null
                    runCatching {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:911")))
                    }
                }) { Text("Call 911", color = PodColors.Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { sosFailed = null; sendSos() }) { Text("Try again") }
                    TextButton(onClick = { sosFailed = null }) { Text("Close") }
                }
            },
        )
    }
}

/**
 * Works out why the map failed: the server answering 404 means it isn't installed;
 * anything else (no connection, timeout, server down) is treated as being offline.
 */
private suspend fun probeMap(serverUrl: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val req = okhttp3.Request.Builder().url("$serverUrl/map/style.json").head().build()
        app.podfamily.net.Http.client.newCall(req).execute().use { r -> if (r.code == 404) "missing" else "offline" }
    } catch (e: Exception) {
        "offline"
    }
}

/** Camera view that shows a whole place circle. */
fun placeTarget(p: Place): CameraTarget {
    val dLat = p.radius * 1.6 / 111_320.0
    val dLon = p.radius * 1.6 / (111_320.0 * kotlin.math.cos(Math.toRadians(p.lat)))
    return CameraTarget.Fit(listOf(p.lat - dLat to p.lon - dLon, p.lat + dLat to p.lon + dLon))
}

@Composable
private fun MemberRow(m: Member, isMe: Boolean, myColor: Int, places: List<Place>, now: Long, metric: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    val loc = m.location
    val stale = loc == null || now - loc.t > STALE_MS
    // The tracked place (same edge tolerance as alerts), falling back to a plain check before tracking starts.
    val place = if (m.placeTracked) m.placeId?.let { id -> places.firstOrNull { it.id == id } }
    else loc?.let { Geofence.placeAt(it.lat, it.lon, places) }
    val since = m.placeSince.takeIf { it > 0 && place != null && m.placeId == place.id }
    val subtitle = when {
        m.lastEvent?.let { it.type == "paused" && (loc == null || it.t > loc.t) } == true -> "Paused sharing"
        m.lastEvent?.let { it.type == "noperm" && (loc == null || it.t > loc.t) } == true -> "Location permission off"
        loc == null -> "No location yet"
        motionLabel(loc, now, metric) != null -> "${motionLabel(loc, now, metric)} · ${timeAgo(loc.t)}"
        place != null -> buildString {
            append("At ${place.name}")
            if (since != null) append(" since ${sinceLabel(since, now)}")
            // Only mention the update time when it's getting old.
            if (since == null || now - loc.t > 10 * 60_000L) append(" · ${timeAgo(loc.t)}")
        }
        else -> "Updated ${timeAgo(loc.t)}"
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(m.name, if (isMe) myColor else m.color, dimmed = stale && !isMe)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (isMe) "${m.name ?: "You"} (you)" else (m.name ?: "?"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = PodColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val bat = loc?.bat
        val charging = loc?.chg == true
        if (bat != null || charging) {
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd, null,
                        tint = when {
                            charging -> PodColors.Good
                            bat != null && bat <= 15 -> PodColors.Danger
                            else -> PodColors.TextDim
                        },
                        modifier = Modifier.size(18.dp),
                    )
                    if (bat != null) Text("$bat%", style = MaterialTheme.typography.bodyMedium, color = if (charging) PodColors.Good else PodColors.TextDim)
                }
                if (charging) Text("Charging", style = MaterialTheme.typography.labelSmall, color = PodColors.Good)
            }
        }
        IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, "More options", tint = PodColors.TextDim) }
    }
}

/** "Driving · 42 mph" / "Moving · 4 mph", only while the reading is fresh. */
/** "3:12 PM" today, "Yesterday 9:05 PM", or "Oct 17" further back. */
fun sinceLabel(t: Long, now: Long): String {
    val day = java.util.Calendar.getInstance().apply { timeInMillis = t }
    val today = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t))
    val sameYear = day.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
    val dayDiff = today.get(java.util.Calendar.DAY_OF_YEAR) - day.get(java.util.Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> time
        sameYear && dayDiff == 1 -> "yesterday $time"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(t))
    }
}

/** Same rule as the "Driving" label: a recent fix at 15 mph or faster. */
fun isDriving(loc: LocPayload, now: Long): Boolean {
    val mps = loc.spd ?: return false
    return now - loc.t <= 3 * 60_000 && mps >= 6.7f
}

fun motionLabel(loc: LocPayload, now: Long, metric: Boolean): String? {
    val mps = loc.spd ?: return null
    if (now - loc.t > 3 * 60_000) return null
    val value = if (metric) mps * 3.6f else mps * 2.23694f
    val unit = if (metric) "km/h" else "mph"
    return when {
        mps >= 6.7f -> "Driving · ${value.toInt()} $unit"
        mps >= 1.0f -> "Moving · ${value.toInt()} $unit"
        else -> null
    }
}

@Composable
private fun StatusPill(status: Pair<String, Color>) {
    Row(
        Modifier.background(PodColors.Surface.copy(alpha = 0.92f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(status.second, 9.dp)
        Spacer(Modifier.width(8.dp))
        Text(status.first, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).background(PodColors.Surface.copy(alpha = 0.92f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = PodColors.Text) }
}

/** Add a new place: pick the exact spot and size on a map, and name it. */
@Composable
fun AddPlaceDialog(
    lat: Double,
    lon: Double,
    onDismiss: () -> Unit,
    onSave: (name: String, lat: Double, lon: Double, radius: Int) -> Unit,
    initialName: String = "",
    initialRadius: Int = 150,
) {
    app.podfamily.ui.places.PlaceMapEditor(
        title = "Add a place",
        lat = lat, lon = lon, radius = initialRadius, name = initialName,
        saveLabel = "Add place",
        onDismiss = onDismiss,
        onDone = onSave,
    )
}
