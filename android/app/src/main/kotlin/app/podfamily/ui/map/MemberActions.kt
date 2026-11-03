package app.podfamily.ui.map

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.podfamily.data.LocPayload
import app.podfamily.data.Member
import app.podfamily.data.OneTimeAlert
import app.podfamily.data.Place
import app.podfamily.ui.components.Hint
import app.podfamily.ui.theme.PodColors

/** A location-history line currently drawn on the map. */
data class TrailView(val memberId: String, val label: String, val hours: Int, val points: List<LocPayload>, val color: Int)

fun hoursLabel(h: Int): String = if (h % 24 == 0 && h >= 48) "${h / 24} days" else "$h hours"

/** Per-person menu: history trail, one-time alerts, mute. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberActionsDialog(
    member: Member,
    isMe: Boolean,
    places: List<Place>,
    oneTime: List<OneTimeAlert>,
    muted: Boolean,
    onTrail: (Int) -> Unit,
    onAddOneTime: (type: String, placeId: String?) -> Unit,
    onRemoveOneTime: (String) -> Unit,
    onMute: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val name = if (isMe) "You" else (member.name ?: "Family member")
    var type by remember { mutableStateOf("leave") }
    var placeId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PodColors.SurfaceHigh,
        title = { Text(name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Location history", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(12, 24, 168).forEach { h ->
                        OutlinedButton(onClick = { onTrail(h) }) { Text(if (h == 168) "7 days" else "$h h") }
                    }
                }
                Hint("Draws the path on the map. How far back it goes depends on ${if (isMe) "your" else "their"} \"Keep location history\" setting.")

                if (!isMe) {
                    Text("Tell me once when $name…", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = type == "arrive", onClick = { type = "arrive" }, label = { Text("Arrives") })
                        FilterChip(selected = type == "leave", onClick = { type = "leave" }, label = { Text("Leaves") })
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = placeId == null, onClick = { placeId = null }, label = { Text("Any place") })
                        places.forEach { p ->
                            FilterChip(selected = placeId == p.id, onClick = { placeId = p.id }, label = { Text(p.name) })
                        }
                    }
                    TextButton(onClick = { onAddOneTime(type, placeId) }) { Text("Add one-time alert") }
                    oneTime.forEach { a ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val where = a.placeId?.let { id -> places.firstOrNull { it.id == id }?.name } ?: "any place"
                            Text(
                                "${if (a.type == "arrive") "Arrives at" else "Leaves"} $where",
                                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onRemoveOneTime(a.id) }) { Icon(Icons.Filled.Close, "Remove", tint = PodColors.TextDim) }
                        }
                    }
                    if (places.isEmpty()) Hint("One-time alerts work with your saved places. Add some in Places.")

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Alerts from $name", style = MaterialTheme.typography.bodyLarge)
                            Hint("Arrivals, departures, check-ins, low battery. SOS always comes through.")
                        }
                        Switch(checked = !muted, onCheckedChange = { onMute(!it) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
