package app.podfamily.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import app.podfamily.ui.map.MapMember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podfamily.data.LocPayload
import app.podfamily.data.PodRepository
import app.podfamily.ui.components.PrimaryButton
import app.podfamily.ui.map.CameraTarget
import app.podfamily.ui.map.MapStyleSource
import app.podfamily.ui.map.MapThemes
import app.podfamily.ui.map.PodMap
import app.podfamily.ui.theme.PodColors
import kotlin.math.roundToInt

const val MIN_PLACE_RADIUS = 50
const val MAX_PLACE_RADIUS = 1000

fun formatRadius(meters: Int, metric: Boolean): String =
    if (metric) "$meters m" else "${(meters * 3.28084 / 10).roundToInt() * 10} ft"

/**
 * Full-screen map for placing a place exactly: drag the map under the pin,
 * and the size circle updates live while you move the slider.
 *
 * [name] == null hides the name field (used when editing an existing place's position).
 *
 * Drawn as a full-screen layer in the main window, NOT a Dialog: dialog windows don't
 * receive system-bar insets, so navigationBarsPadding() is zero there and the buttons end
 * up under the phone's navigation bar. Callers emit it at screen level so it covers the screen.
 */
@Composable
fun PlaceMapEditor(
    title: String,
    lat: Double,
    lon: Double,
    radius: Int,
    name: String?,
    excludePlaceId: String? = null,
    saveLabel: String = "Save",
    onDismiss: () -> Unit,
    onDone: (name: String, lat: Double, lon: Double, radius: Int) -> Unit,
) {
    val ctx = LocalContext.current
    val repo = remember { PodRepository.get(ctx) }
    val state by repo.state.collectAsStateWithLifecycle()
    val family = state.family ?: return
    val myLoc: LocPayload? = state.members[repo.myId]?.location

    var centerLat by remember { mutableDoubleStateOf(lat) }
    var centerLon by remember { mutableDoubleStateOf(lon) }
    var size by remember { mutableFloatStateOf(radius.coerceIn(MIN_PLACE_RADIUS, MAX_PLACE_RADIUS).toFloat()) }
    var placeName by remember { mutableStateOf(name.orEmpty()) }
    var camera by remember { mutableStateOf<CameraTarget?>(null) }
    val mapStyle by produceState<MapStyleSource?>(null, state.mapTheme, family.serverUrl) {
        value = MapStyleSource(state.mapTheme, MapThemes.load(family.serverUrl, state.mapTheme))
    }

    // Family dots stay visible, so you can drop a place right where someone is.
    val members = state.members.values.mapNotNull { m ->
        val loc = m.location ?: return@mapNotNull null
        if (m.name == null && m.deviceId != repo.myId) return@mapNotNull null
        val isMe = m.deviceId == repo.myId
        MapMember(
            m.deviceId, if (isMe) "You" else (m.name ?: "?"),
            (if (isMe) state.myColor else m.color) ?: 0xFF5EC8F2.toInt(),
            loc.lat, loc.lon, stale = System.currentTimeMillis() - loc.t > 30 * 60_000L,
        )
    }
    // Measured heights of the top bar and bottom panel: the map's center (where the pin
    // and circle are) is placed in the middle of the visible area between them.
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(PodColors.Background)) {
        PodMap(
            styleUrl = "${family.serverUrl}/map/style.json",
            theme = mapStyle,
            members = members,
            places = state.places.filter { it.id != excludePlaceId },
            camera = camera,
            editStart = lat to lon,
            editRadius = size.roundToInt(),
            onCenterChange = { la, lo -> centerLat = la; centerLon = lo },
            mapPaddingPx = if (topPx > 0 && bottomPx > 0) intArrayOf(0, topPx, 0, bottomPx) else null,
            onMemberTap = { id -> state.members[id]?.location?.let { camera = CameraTarget.Center(it.lat, it.lon) } },
            onPlaceTap = {},
            onLongPress = { _, _ -> },
            onMapUnavailable = {},
            modifier = Modifier.fillMaxSize(),
        )

        // The pin, centered in the visible map area; its tip marks the exact spot.
        Box(
            Modifier.fillMaxSize().padding(
                top = with(density) { topPx.toDp() },
                bottom = with(density) { bottomPx.toDp() },
            )
        ) {
            Box(Modifier.align(Alignment.Center)) {
                Box(Modifier.size(8.dp).align(Alignment.Center).background(PodColors.Text, CircleShape).border(2.dp, PodColors.Background, CircleShape))
                Icon(
                    Icons.Filled.Place, null, tint = PodColors.Accent,
                    modifier = Modifier.size(44.dp).align(Alignment.Center).offset(y = (-20).dp),
                )
            }
        }

        // Top bar
        Row(
            Modifier.fillMaxWidth().onSizeChanged { topPx = it.height }.statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.background(PodColors.SurfaceHigh, CircleShape)) {
                Icon(Icons.Filled.Close, "Cancel", tint = PodColors.Text)
            }
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier.weight(1f).background(PodColors.SurfaceHigh, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("Drag the map to move the pin", style = MaterialTheme.typography.bodySmall, color = PodColors.TextDim)
            }
            if (myLoc != null) {
                Spacer(Modifier.width(10.dp))
                IconButton(
                    onClick = { camera = CameraTarget.Center(myLoc.lat, myLoc.lon) },
                    modifier = Modifier.background(PodColors.SurfaceHigh, CircleShape),
                ) { Icon(Icons.Filled.MyLocation, "Move to my location", tint = PodColors.Text) }
            }
        }

        // Bottom panel: kept compact so most of the screen stays map.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { bottomPx = it.height }
                .background(PodColors.Surface, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (name != null) {
                OutlinedTextField(
                    placeName, { placeName = it.take(30) }, singleLine = true,
                    placeholder = { Text("Name (e.g. Home, School)") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatRadius(size.roundToInt(), state.useMetric),
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(72.dp),
                )
                Slider(
                    value = size, onValueChange = { size = it },
                    valueRange = MIN_PLACE_RADIUS.toFloat()..MAX_PLACE_RADIUS.toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }
            PrimaryButton(
                saveLabel,
                onClick = { onDone(placeName.trim(), centerLat, centerLon, size.roundToInt()) },
                enabled = name == null || placeName.isNotBlank(),
            )
        }
    }
}
