package app.podfamily.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.podfamily.system.Permissions
import app.podfamily.ui.components.Card
import app.podfamily.ui.components.Hint
import app.podfamily.ui.components.PrimaryButton
import app.podfamily.ui.components.SecondaryButton
import app.podfamily.ui.theme.PodColors

/**
 * Guided, one-step-at-a-time permission setup. Each step says *why* it is
 * needed in plain words - most "the app stopped working" problems on Android
 * come from one of these being off.
 */
@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    // Re-read on every resume / result.
    val fine = remember(refresh) { Permissions.hasFine(ctx) }
    val background = remember(refresh) { Permissions.hasBackgroundLocation(ctx) }
    val notif = remember(refresh) { Permissions.hasNotifications(ctx) }
    val battery = remember(refresh) { Permissions.isIgnoringBatteryOptimizations(ctx) }

    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) ctx.startActivity(Permissions.appSettingsIntent(ctx))
        refresh++
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("A few settings", style = MaterialTheme.typography.headlineSmall)
        Hint("Pod needs these to keep your family up to date, even when your phone is in your pocket.")

        Step(
            n = 1, done = fine, title = "Precise location",
            why = "So your family sees where you are, not just the neighborhood.",
            action = "Allow location",
        ) {
            locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        Step(
            n = 2, done = background, enabled = fine, title = "Location all the time",
            why = "Choose \"Allow all the time\". Without it, sharing stops when the app is closed.",
            action = "Open setting",
        ) {
            if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Step(
                n = 3, done = notif, title = "Notifications",
                why = "For arrivals, departures and SOS alerts from your family.",
                action = "Allow notifications",
            ) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        Step(
            n = if (Build.VERSION.SDK_INT >= 33) 4 else 3, done = battery, title = "Unrestricted battery",
            why = "Stops Android from putting Pod to sleep. Pod is designed to use little battery.",
            action = "Allow",
        ) { batteryLauncher.launch(Permissions.batteryOptimizationIntent(ctx)) }

        TextButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Samsung, Xiaomi, OnePlus? Extra steps for your phone") }

        Spacer(Modifier.height(4.dp))
        PrimaryButton("Continue", onDone, enabled = background)
        if (background && !battery) {
            Hint("You can continue, but without unrestricted battery some phones will stop sharing after a while.")
        }
    }
}

@Composable
private fun Step(n: Int, done: Boolean, title: String, why: String, action: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).background(if (done) PodColors.Good else PodColors.SurfaceHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Filled.Check, null, tint = Color(0xFF0E1013), modifier = Modifier.size(18.dp))
                else Text("$n", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Hint(why)
        if (!done) SecondaryButton(action, onClick, enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}
