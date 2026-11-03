package app.podfamily.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podfamily.ui.theme.PodColors

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(text)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
    ) { Text(text) }
}

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PodColors.Danger, contentColor = Color.White),
    ) { Text(text) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodTopBar(title: String, onBack: (() -> Unit)?) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = PodColors.Background),
    )
}

/** A round colored avatar with the member's initial. */
@Composable
fun Avatar(name: String?, color: Int?, size: Dp = 44.dp, dimmed: Boolean = false) {
    val c = Color(color ?: 0xFF5EC8F2.toInt())
    Box(
        Modifier.size(size).background(if (dimmed) c.copy(alpha = 0.35f) else c, CircleShape)
            .border(2.dp, PodColors.Background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (name?.trim()?.firstOrNull()?.uppercaseChar() ?: '?').toString(),
            color = Color(0xFF0E1013), fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp,
        )
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().background(PodColors.Surface, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
fun StatusDot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

@Composable
fun Banner(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(color.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = PodColors.Text)
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = PodColors.TextDim, textAlign = TextAlign.Start)
}

fun timeAgo(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0) return "never"
    val s = (System.currentTimeMillis() - epochMillis) / 1000
    return when {
        s < 10 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} hr ago"
        else -> "${s / 86_400} d ago"
    }
}

/** "From 10:00 PM  [-] [+]" stepper, 30-minute steps. */
@Composable
fun TimeStepper(label: String, minutes: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(56.dp))
        Text(
            app.podfamily.data.formatMinutes(minutes),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.TextButton(onClick = { onChange((minutes - 30 + 1440) % 1440) }) { Text("-30m") }
        androidx.compose.material3.TextButton(onClick = { onChange((minutes + 30) % 1440) }) { Text("+30m") }
    }
}
