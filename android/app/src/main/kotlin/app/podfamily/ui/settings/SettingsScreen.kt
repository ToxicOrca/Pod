package app.podfamily.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import app.podfamily.ui.map.MapThemes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podfamily.BuildConfig
import app.podfamily.data.Member
import app.podfamily.data.MemberColors
import app.podfamily.data.PodRepository
import app.podfamily.location.LocationService
import app.podfamily.system.DiagLog
import app.podfamily.system.Permissions
import app.podfamily.system.ServiceController
import app.podfamily.ui.components.Avatar
import app.podfamily.ui.components.Card
import app.podfamily.ui.components.DangerButton
import app.podfamily.ui.components.Hint
import app.podfamily.ui.components.PodTopBar
import app.podfamily.ui.components.SecondaryButton
import app.podfamily.ui.components.TimeStepper
import app.podfamily.ui.components.timeAgo
import app.podfamily.ui.theme.PodColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { PodRepository.get(ctx) }
    val state by repo.state.collectAsStateWithLifecycle()
    val conn by repo.conn.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }

    var name by remember(state.myName) { mutableStateOf(state.myName) }
    var color by remember(state.myColor) { mutableStateOf(state.myColor) }
    var removing by remember { mutableStateOf<Member?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val family = state.family ?: return
    if (showLicenses) {
        LicensesScreen(onBack = { showLicenses = false })
        return
    }
    val isAdmin = repo.isAdmin

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        PodTopBar("Settings", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // --- You ---
            Card {
                SectionTitle("You")
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MemberColors.forEach { c ->
                        Box(
                            Modifier.size(36.dp).background(Color(c), CircleShape)
                                .border(3.dp, if (c == color) PodColors.Text else Color.Transparent, CircleShape)
                                .clickable { color = c }
                        )
                    }
                }
                if (name.trim() != state.myName || color != state.myColor) {
                    SecondaryButton("Save", onClick = {
                        scope.launch { repo.updateProfile(name, color).onFailure { snackbar.showSnackbar(it.message ?: "Failed") } }
                    }, enabled = name.isNotBlank())
                }
            }

            // --- Sharing ---
            Card {
                SectionTitle("Sharing")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Share my location", style = MaterialTheme.typography.titleMedium)
                        Hint(if (state.sharingEnabled) "Your family can see where you are." else "Paused. Your family was told you paused.")
                    }
                    Switch(checked = state.sharingEnabled, onCheckedChange = { on -> scope.launch { repo.setSharing(on) } })
                }
                Text("Keep location history for", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 7, 30).forEach { d ->
                        FilterChip(selected = state.historyDays == d, onClick = { repo.setHistoryDays(d) }, label = { Text(if (d == 1) "1 day" else "$d days") })
                    }
                }
                Hint("History is stored encrypted on your server and deleted automatically after this.")
            }

            // --- Family ---
            Card {
                SectionTitle("Family members")
                state.members.values.sortedBy { it.joinedAt }.forEach { m ->
                    val isMe = m.deviceId == family.deviceId
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m.name ?: if (isMe) state.myName else null, if (isMe) state.myColor else m.color, size = 36.dp, dimmed = !m.approved)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                (m.name ?: if (isMe) state.myName else "New phone") + if (isMe) " (you)" else "",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Hint(
                                when {
                                    m.role == "admin" -> "Admin"
                                    !m.approved && isAdmin -> "Not verified - remove if you don't recognize it"
                                    m.name == null -> "Setting up..."
                                    else -> "Joined"
                                }
                            )
                        }
                        if (isAdmin && !isMe) {
                            TextButton(onClick = { removing = m }) { Text("Remove", color = PodColors.Danger) }
                        }
                    }
                }
            }

            // --- Recovery kit ---
            Card {
                app.podfamily.ui.RecoveryKitSection(onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } })
            }

            // --- What I share ---
            Card {
                SectionTitle("What I share")
                Hint("Choose what your family can see about you. Turning something off takes effect right away. Pausing all sharing is under Sharing above.")
                ShareRow("Precise location", "Off = your location is rounded to about 1 km, so your exact address isn't shown.", state.preciseLocation) { on ->
                    scope.launch { repo.updateSharing { it.copy(preciseLocation = on) } }
                }
                ShareRow("Battery level", "Your battery percentage.", state.shareBattery) { on ->
                    scope.launch { repo.updateSharing { it.copy(shareBattery = on) } }
                }
                ShareRow("Charging status", "Whether your phone is plugged in.", state.shareCharging) { on ->
                    scope.launch { repo.updateSharing { it.copy(shareCharging = on) } }
                }
                ShareRow("Driving & speed", "Shows \"Driving · 42 mph\" when you're moving. Needs precise location.", state.shareSpeed) { on ->
                    scope.launch { repo.updateSharing { it.copy(shareSpeed = on) } }
                }
                ShareRow("Arrivals & departures", "Alerts your family when you arrive at or leave a place.", state.shareArrivals) { on ->
                    scope.launch { repo.updateSharing { it.copy(shareArrivals = on) } }
                }
                Hint("SOS always includes your exact location, whatever these say.")
            }

            // --- Map & display ---
            Card {
                SectionTitle("Map & display")
                Text("Map theme", style = MaterialTheme.typography.titleMedium)
                MapThemes.all.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { repo.updateSettings { it.copy(mapTheme = t.id) } }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.mapTheme == t.id, onClick = { repo.updateSettings { it.copy(mapTheme = t.id) } })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.bodyLarge)
                            Hint(t.description)
                        }
                    }
                }
                Text("Speed units", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !state.useMetric, onClick = { repo.updateSettings { it.copy(useMetric = false) } }, label = { Text("mph") })
                    FilterChip(selected = state.useMetric, onClick = { repo.updateSettings { it.copy(useMetric = true) } }, label = { Text("km/h") })
                }
                ShareRow(
                    "Address search",
                    "Lets you search for an address or business when adding a place. The text you type and a rough area (~11 km) go to OpenStreetMap search services, and house addresses also to the US Census geocoder; nobody's live location is sent.",
                    state.addressSearch,
                ) { on -> repo.updateSettings { it.copy(addressSearch = on) } }
            }

            // --- Notifications ---
            Card {
                SectionTitle("Notifications")
                Hint("Choose whose arrivals, departures and check-ins alert this phone. SOS alerts always come through. Per-place alerts are in Places.")
                val others = state.members.values.filter { it.deviceId != family.deviceId && it.name != null }.sortedBy { it.name }
                if (others.isEmpty()) Hint("Nobody else in the family yet.")
                others.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m.name, m.color, size = 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(m.name ?: "?", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(
                            checked = m.deviceId !in state.mutedMembers,
                            onCheckedChange = { on -> repo.setMemberMuted(m.deviceId, !on) },
                        )
                    }
                }
                ShareRow("Low battery alerts", "When someone's phone drops to 15% and isn't charging.", state.lowBatteryAlerts) { on ->
                    repo.updateSettings { it.copy(lowBatteryAlerts = on) }
                }
                Text("Warn me when a phone stops updating", style = MaterialTheme.typography.bodyLarge)
                Hint("Usually means a dead battery, no signal, or Android stopped Pod.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Off", 1 to "1 h", 3 to "3 h", 6 to "6 h", 12 to "12 h").forEach { (h, label) ->
                        FilterChip(selected = state.staleAlertHours == h, onClick = { repo.updateSettings { it.copy(staleAlertHours = h) } }, label = { Text(label) })
                    }
                }
                ShareRow("Quiet hours", "No alerts during these hours. SOS always comes through.", state.quietHours) { on ->
                    repo.updateSettings { it.copy(quietHours = on) }
                }
                if (state.quietHours) {
                    TimeStepper("From", state.quietStartMin) { v -> repo.updateSettings { it.copy(quietStartMin = v) } }
                    TimeStepper("To", state.quietEndMin) { v -> repo.updateSettings { it.copy(quietEndMin = v) } }
                }
            }

            // --- Reliability ---
            Card {
                SectionTitle("Is everything working?")
                val tick = refresh
                CheckRow("Location: allowed all the time", remember(tick) { Permissions.hasBackgroundLocation(ctx) })
                val precise = remember(tick) { !Permissions.isApproximateOnly(ctx) }
                CheckRow("Location: precise", precise)
                if (!precise) Hint("Your family sees you about 2 km off. Open app settings > Permissions > Location and turn on \"Use precise location\".")
                CheckRow("Battery: unrestricted", remember(tick) { Permissions.isIgnoringBatteryOptimizations(ctx) })
                CheckRow("Notifications: allowed", remember(tick) { Permissions.hasNotifications(ctx) })
                val sosRings = remember(tick) { app.podfamily.system.Notifications.sosBypassesDnd(ctx) }
                CheckRow("SOS rings during Do Not Disturb", sosRings)
                if (!sosRings) {
                    Hint("Android only lets you turn this on yourself: open SOS alerts and allow \"Override Do Not Disturb\".")
                    SecondaryButton("Open SOS alert settings", onClick = {
                        runCatching { ctx.startActivity(app.podfamily.system.Notifications.sosChannelSettingsIntent(ctx)) }
                        refresh++
                    })
                }
                CheckRow("Background service running", remember(tick, conn) { LocationService.isRunning })
                CheckRow("Connected to server", conn.online)
                Hint("Last sync: ${timeAgo(conn.lastSyncAt)} · Last location sent: ${timeAgo(conn.lastUploadAt)}")
                conn.error?.let { Hint("Last error: $it") }
                SecondaryButton("Open app settings", onClick = { ctx.startActivity(Permissions.appSettingsIntent(ctx)) })
                SecondaryButton("Restart background service", onClick = {
                    ServiceController.stop(ctx)
                    ServiceController.ensureRunning(ctx, "manual restart", force = true)
                    refresh++
                })
            }

            // --- Diagnostics ---
            Card {
                SectionTitle("Diagnostics")
                Hint("A local log to help fix problems. It never contains locations, names or keys, and is never uploaded.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(if (showLog) "Hide log" else "Show log", onClick = { showLog = !showLog }, modifier = Modifier.weight(1f))
                    SecondaryButton("Copy log", onClick = {
                        clipboard.setText(AnnotatedString(DiagLog.snapshot().joinToString("\n")))
                        scope.launch { snackbar.showSnackbar("Log copied") }
                    }, modifier = Modifier.weight(1f))
                }
                if (showLog) {
                    Text(
                        DiagLog.snapshot().takeLast(150).joinToString("\n"),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = PodColors.TextDim,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                            .background(PodColors.Background, RoundedCornerShape(10.dp)).padding(10.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }

            // --- About ---
            Card {
                SectionTitle("About")
                Hint("Pod Family Location ${BuildConfig.VERSION_NAME} · self-hosted, end-to-end encrypted.")
                app.podfamily.ui.UpdateSettingsRow()
                Hint("Server: ${family.serverUrl.removePrefix("https://")}")
                Hint("This phone: ${family.deviceId.take(8)}")
                SecondaryButton("Open-source licenses", onClick = { showLicenses = true })
            }

            DangerButton(if (isAdmin) "Delete family" else "Leave family", onClick = { confirmLeave = true })
            Spacer(Modifier.height(24.dp))
        }
        SnackbarHost(snackbar)
    }

    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = PodColors.SurfaceHigh,
            title = { Text("Remove ${m.name ?: "this phone"}?") },
            text = { Text("They'll stop seeing the family right away. A new family key is created so they can't read anything new.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch { repo.removeMember(m.deviceId).onFailure { snackbar.showSnackbar(it.message ?: "Failed") } }
                }) { Text("Remove", color = PodColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = PodColors.SurfaceHigh,
            title = { Text(if (isAdmin) "Delete the whole family?" else "Leave the family?") },
            text = {
                Text(
                    if (isAdmin) "Everyone is removed and all family data on the server is deleted. This can't be undone."
                    else "You'll stop sharing and seeing your family. You can rejoin later with a new invite."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch {
                        repo.leaveFamily()
                            .onSuccess { ServiceController.stop(ctx) }
                            .onFailure { snackbar.showSnackbar(it.message ?: "Failed") }
                    }
                }) { Text(if (isAdmin) "Delete" else "Leave", color = PodColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ShareRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(hint)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun CheckRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.Check else Icons.Filled.Close, null,
            tint = if (ok) PodColors.Good else PodColors.Danger, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
