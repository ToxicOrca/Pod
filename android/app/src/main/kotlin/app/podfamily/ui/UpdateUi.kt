package app.podfamily.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podfamily.BuildConfig
import app.podfamily.ui.components.Banner
import app.podfamily.ui.components.Hint
import app.podfamily.ui.components.SecondaryButton
import app.podfamily.ui.theme.PodColors
import app.podfamily.update.Updater
import kotlinx.coroutines.launch

/** Banner on the map when an update is ready. */
@Composable
fun UpdateBanner() {
    val info by Updater.available.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val i = info ?: return
    Box(Modifier.clickable { open = true }) {
        Banner("Pod ${i.versionName} is ready. Tap to update. Your family and settings stay as they are.", PodColors.Accent)
    }
    if (open) UpdateDialog(onDismiss = { open = false })
}

@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by Updater.available.collectAsStateWithLifecycle()
    val progress by Updater.progress.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val canInstall = remember(tick) { Updater.canInstall(ctx) }
    val i = info ?: run { onDismiss(); return }

    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        containerColor = PodColors.SurfaceHigh,
        title = { Text("Update to ${i.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (i.notes.isNotBlank()) {
                    Text("What's new", style = MaterialTheme.typography.titleMedium)
                    Text(i.notes, style = MaterialTheme.typography.bodyMedium)
                }
                Hint("Takes about a minute. Your family, places and settings are kept, and nothing needs to be set up again.")
                if (!canInstall) {
                    Hint("First time only: Android will ask you to allow Pod to install updates. Turn it on, then come back here.")
                }
                progress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading… ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                }
                error?.let { Text(it, color = PodColors.Danger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = progress == null,
                onClick = {
                    error = null
                    if (!canInstall) {
                        ctx.startActivity(Updater.installPermissionIntent(ctx))
                    } else {
                        scope.launch { Updater.downloadAndInstall(ctx).onFailure { error = it.message ?: "Update failed" } }
                    }
                },
            ) { Text(if (canInstall) "Update" else "Allow updates") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = progress == null) { Text("Later") } },
    )
}

/** Settings > About: version and a manual check. */
@Composable
fun UpdateSettingsRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by Updater.available.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    if (!Updater.enabled) {
        Hint("Debug build (version code ${BuildConfig.VERSION_CODE}): updates come from CI, not the server.")
        return
    }
    when {
        info != null -> SecondaryButton("Update to ${info?.versionName}", onClick = { open = true })
        else -> SecondaryButton(if (checking) "Checking…" else "Check for updates", enabled = !checking, onClick = {
            checking = true
            scope.launch { Updater.check(ctx, force = true); checking = false; checked = true }
        })
    }
    if (checked && info == null) Hint("You're on the latest version.")
    if (open) UpdateDialog(onDismiss = { open = false })
}
