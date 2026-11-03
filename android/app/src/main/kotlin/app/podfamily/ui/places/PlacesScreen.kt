package app.podfamily.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podfamily.data.AddressSearch
import app.podfamily.data.LocPayload
import app.podfamily.ui.components.SecondaryButton
import app.podfamily.ui.components.TimeStepper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.input.ImeAction
import app.podfamily.data.Place
import app.podfamily.data.PlaceAlertPref
import app.podfamily.data.PodRepository
import app.podfamily.ui.components.Card
import app.podfamily.ui.components.Hint
import app.podfamily.ui.components.PodTopBar
import app.podfamily.ui.components.PrimaryButton
import app.podfamily.ui.map.AddPlaceDialog
import app.podfamily.ui.theme.PodColors
import kotlinx.coroutines.launch
import java.util.UUID

fun alertSummary(pref: PlaceAlertPref): String =
    baseSummary(pref) + if (pref.scheduled && (pref.arrive || pref.leave)) " (scheduled)" else ""

private fun baseSummary(pref: PlaceAlertPref): String = when {
    pref.arrive && pref.leave -> "Alerts: arrivals & departures"
    pref.arrive -> "Alerts: arrivals only"
    pref.leave -> "Alerts: departures only"
    else -> "Alerts off"
}

@Composable
fun PlacesScreen(onBack: () -> Unit, onShowOnMap: (Place) -> Unit = {}) {
    val ctx = LocalContext.current
    val repo = remember { PodRepository.get(ctx) }
    val state by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Place?>(null) }
    var searching by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<AddressSearch.Result?>(null) }
    val myLoc = state.members[repo.myId]?.location

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        PodTopBar("Places", onBack)
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Hint("Tap a place to see it on the map. Tap the pencil to rename, move or resize it, or choose which alerts you get. Tip: long-press anywhere on the map to add a place there.") }
            if (state.places.isEmpty()) {
                item { Card { Text("No places yet", style = MaterialTheme.typography.titleMedium); Hint("Add Home, School or Work to get arrival alerts.") } }
            }
            items(state.places, key = { it.id }) { p ->
                val pref = state.placeAlerts[p.id] ?: PlaceAlertPref()
                Card(Modifier.clickable { onShowOnMap(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Place, null, tint = PodColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text("${formatRadius(p.radius, state.useMetric)} · ${alertSummary(pref)}", style = MaterialTheme.typography.bodyMedium, color = PodColors.TextDim)
                        }
                        if (!pref.arrive && !pref.leave) Icon(Icons.Filled.NotificationsOff, "Alerts off", tint = PodColors.TextDim)
                        IconButton(onClick = { editing = p }) { Icon(Icons.Filled.Edit, "Edit ${p.name}", tint = PodColors.TextDim) }
                    }
                }
            }
        }
        SnackbarHost(snackbar)
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            PrimaryButton("Add my current location", onClick = { adding = true }, enabled = myLoc != null)
            if (myLoc == null) Hint("Waiting for your location...", Modifier.padding(top = 8.dp))
            if (state.addressSearch) {
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Search for an address", onClick = { searching = true })
            }
        }
    }

    if (adding && myLoc != null) {
        AddPlaceDialog(
            lat = myLoc.lat, lon = myLoc.lon,
            onDismiss = { adding = false },
            onSave = { name, lat, lon, radius ->
                adding = false
                scope.launch {
                    repo.savePlaces(state.places + Place(UUID.randomUUID().toString(), name, lat, lon, radius))
                        .onFailure { snackbar.showSnackbar(it.message ?: "Could not save") }
                }
            },
        )
    }

    if (searching) {
        AddressSearchDialog(
            near = myLoc,
            metric = state.useMetric,
            onDismiss = { searching = false },
            onPick = { r -> searching = false; picked = r },
        )
    }

    picked?.let { r ->
        AddPlaceDialog(
            lat = r.lat, lon = r.lon,
            initialName = r.title.take(30),
            onDismiss = { picked = null },
            onSave = { name, lat, lon, radius ->
                picked = null
                scope.launch {
                    repo.savePlaces(state.places + Place(UUID.randomUUID().toString(), name, lat, lon, radius))
                        .onSuccess { snackbar.showSnackbar("Added $name") }
                        .onFailure { snackbar.showSnackbar(it.message ?: "Could not save") }
                }
            },
        )
    }

    editing?.let { p ->
        EditPlaceDialog(
            place = p,
            pref = state.placeAlerts[p.id] ?: PlaceAlertPref(),
            metric = state.useMetric,
            onDismiss = { editing = null },
            onSave = { updated, pref ->
                editing = null
                repo.setPlaceAlerts(updated.id, pref)
                if (updated != p) scope.launch {
                    repo.updatePlace(updated).onFailure { snackbar.showSnackbar(it.message ?: "Could not save") }
                }
            },
            onDelete = {
                editing = null
                scope.launch { repo.deletePlace(p.id).onFailure { snackbar.showSnackbar(it.message ?: "Could not delete") } }
            },
        )
    }
}

/**
 * Edit a place. Name, size and position are shared with the whole family;
 * the alert switches only affect this phone.
 */
@Composable
fun EditPlaceDialog(
    place: Place,
    pref: PlaceAlertPref,
    metric: Boolean,
    onDismiss: () -> Unit,
    onSave: (Place, PlaceAlertPref) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(place.name) }
    var radius by remember { mutableStateOf(place.radius) }
    var lat by remember { mutableStateOf(place.lat) }
    var lon by remember { mutableStateOf(place.lon) }
    var adjusting by remember { mutableStateOf(false) }
    var arrive by remember { mutableStateOf(pref.arrive) }
    var leave by remember { mutableStateOf(pref.leave) }
    var scheduled by remember { mutableStateOf(pref.scheduled) }
    var days by remember { mutableStateOf(pref.days) }
    var startMin by remember { mutableStateOf(pref.startMin) }
    var endMin by remember { mutableStateOf(pref.endMin) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = PodColors.SurfaceHigh,
            title = { Text("Delete ${place.name}?") },
            text = { Text("It's removed for the whole family, and nobody gets alerts for it anymore.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = PodColors.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
        return
    }
    if (adjusting) {
        PlaceMapEditor(
            title = "Move or resize ${name.ifBlank { place.name }}",
            lat = lat, lon = lon, radius = radius, name = null,
            excludePlaceId = place.id, saveLabel = "Done",
            onDismiss = { adjusting = false },
            onDone = { _, la, lo, r -> lat = la; lon = lo; radius = r; adjusting = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PodColors.SurfaceHigh,
        title = { Text("Edit place") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, label = { Text("Name") }, singleLine = true)
                val moved = lat != place.lat || lon != place.lon
                Text(
                    "Size: ${formatRadius(radius, metric)}" + if (moved) " · moved" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SecondaryButton("Move or resize on the map", onClick = { adjusting = true })
                Hint("Name, size and location change for everyone in the family.")
                Spacer(Modifier.width(1.dp))
                Text("My alerts for this place", style = MaterialTheme.typography.titleMedium)
                SwitchRow("When someone arrives", arrive) { arrive = it }
                SwitchRow("When someone leaves", leave) { leave = it }
                SwitchRow("Only at certain times", scheduled) { scheduled = it }
                if (scheduled) {
                    DayPicker(days) { days = it }
                    TimeStepper("From", startMin) { startMin = it }
                    TimeStepper("To", endMin) { endMin = it }
                    Hint("Example: School, weekdays 7:00 AM to 4:00 PM.")
                }
                Hint("Alert choices only affect this phone.")
                TextButton(onClick = { confirmDelete = true }) { Text("Delete place", color = PodColors.Danger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        place.copy(name = name.trim(), radius = radius, lat = lat, lon = lon),
                        PlaceAlertPref(arrive, leave, scheduled, days, startMin, endMin),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Type an address or place name, pick a result. */
@Composable
fun AddressSearchDialog(near: LocPayload?, onDismiss: () -> Unit, onPick: (AddressSearch.Result) -> Unit, metric: Boolean = false) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AddressSearch.Result>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run() {
        if (query.isBlank() || busy) return
        busy = true; error = null
        scope.launch {
            try {
                results = AddressSearch.search(query, near)
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
            }
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PodColors.SurfaceHigh,
        title = { Text("Find an address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    query, { query = it }, singleLine = true,
                    label = { Text("Address or place name") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run() }),
                )
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = PodColors.Danger) }
                results?.let { list ->
                    if (list.isEmpty()) Hint("No matches. For a house address, include the city and state or the ZIP code, like \"123 Main St, Portland OR\".")
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(list) { r ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(r) }.padding(vertical = 10.dp)) {
                                Text(r.title, style = MaterialTheme.typography.bodyLarge)
                                val dist = r.distanceMeters?.let { d ->
                                    if (metric) "%.1f km".format(d / 1000) else "%.1f mi".format(d / 1609.34)
                                }
                                val line = listOfNotNull(dist, r.subtitle.takeIf { it.isNotBlank() }).joinToString(" · ")
                                if (line.isNotBlank()) Hint(line)
                            }
                        }
                    }
                }
                Hint("Search by OpenStreetMap, plus the US Census for house addresses. Only what you type and a rough area are sent.")
            }
        },
        confirmButton = { TextButton(onClick = { run() }, enabled = query.isNotBlank() && !busy) { Text("Search") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DayPicker(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l ->
            val d = i + 1
            val on = d in days
            Box(
                Modifier.size(32.dp)
                    .background(if (on) PodColors.Accent else PodColors.SurfaceHighest, CircleShape)
                    .clickable { onChange(if (on) days - d else days + d) },
                contentAlignment = Alignment.Center,
            ) {
                Text(l, color = if (on) Color(0xFF00151F) else PodColors.Text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
