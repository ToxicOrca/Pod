package app.podfamily.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podfamily.crypto.RecoveryKit
import app.podfamily.data.PodRepository
import app.podfamily.ui.components.Banner
import app.podfamily.ui.components.Hint
import app.podfamily.ui.components.PodTopBar
import app.podfamily.ui.components.PrimaryButton
import app.podfamily.ui.components.SecondaryButton
import app.podfamily.ui.theme.PodColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saving a kit must never leave a half-written or empty file that someone thinks is a backup.
 * So: the kit is encrypted FIRST (before any file exists), kept here in memory (encrypted)
 * while the file picker is open (survives rotation), then written on an app-wide scope that
 * isn't cancelled when the screen closes, read back to verify, and deleted again on failure.
 */
private object KitSave {
    @Volatile var pendingText: String? = null
    val busy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val result = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
}

/**
 * Settings section: save an encrypted recovery kit file.
 * Flow: choose passphrase -> encrypt -> choose where to save -> write + verify.
 */
@Composable
fun RecoveryKitSection(onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { PodRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    var askPass by remember { mutableStateOf(false) }
    val busy by KitSave.busy.collectAsStateWithLifecycle()
    val result by KitSave.result.collectAsStateWithLifecycle()
    val state by repo.state.collectAsStateWithLifecycle()
    val savedAt = state.recoveryKitSavedAt
    androidx.compose.runtime.LaunchedEffect(result) {
        result?.let { onMessage(it); KitSave.result.value = null }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val text = KitSave.pendingText
        KitSave.pendingText = null
        if (uri == null || text == null) {
            KitSave.busy.value = false
            return@rememberLauncherForActivityResult
        }
        val appCtx = ctx.applicationContext
        KitSave.scope.launch {
            val cr = appCtx.contentResolver
            try {
                val out = try { cr.openOutputStream(uri, "wt") } catch (e: Exception) { null } ?: cr.openOutputStream(uri, "w")
                    ?: throw java.io.IOException("Couldn't open the file")
                out.use { it.write(text.toByteArray()) }
                // Read it back: only a kit that's really on disk counts as saved.
                val back = cr.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                if (back != text) throw java.io.IOException("The saved file didn't match")
                repo.markRecoveryKitSaved()
                KitSave.result.value = "Recovery kit saved"
            } catch (e: Exception) {
                runCatching { android.provider.DocumentsContract.deleteDocument(cr, uri) }
                KitSave.result.value = "Couldn't save the kit (${e.message}). Nothing was saved, please try again."
            } finally {
                KitSave.busy.value = false
            }
        }
    }

    Text("Recovery kit", style = MaterialTheme.typography.titleLarge)
    Hint(
        "An encrypted backup of this phone's Pod identity. If this phone is lost, broken, reset or replaced, " +
            "restore the kit on the new phone and you're back in the family instantly, with nothing to rebuild. " +
            "You only need to save it once."
    )
    if (savedAt > 0) {
        Banner("Saved ${SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(savedAt))}. Keep the file and passphrase somewhere safe, like a password manager.", PodColors.Good)
    } else {
        Banner(
            if (repo.isAdmin) "Not saved yet. You're the family admin, so without a kit, losing this phone would freeze your family."
            else "Not saved yet. Without a kit, losing this phone means asking the admin for a new invite.",
            PodColors.Warn,
        )
    }
    SecondaryButton(if (busy) "Saving..." else if (savedAt > 0) "Save a new copy" else "Save recovery kit", onClick = { askPass = true }, enabled = !busy)

    if (askPass) {
        PassphraseDialog(
            onDismiss = { askPass = false },
            onConfirm = { p ->
                askPass = false
                KitSave.busy.value = true
                // Encrypt before creating any file; leaving the screen now simply cancels, harmlessly.
                scope.launch {
                    try {
                        KitSave.pendingText = repo.exportRecoveryKit(p)
                        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        saver.launch("pod-recovery-$day.podkit")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        KitSave.busy.value = false
                        throw e
                    } catch (e: Exception) {
                        KitSave.busy.value = false
                        onMessage("Couldn't create the kit: ${e.message}")
                    }
                }
            },
        )
    }
}

@Composable
private fun PassphraseDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val tooShort = p1.length < RecoveryKit.MIN_PASSPHRASE
    val mismatch = p2.isNotEmpty() && p1 != p2
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PodColors.SurfaceHigh,
        title = { Text("Choose a passphrase") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint("The kit is useless without this passphrase, and it can't be recovered if you forget it. Anyone with both the file and the passphrase could act as this phone, so make it strong.")
                OutlinedTextField(
                    p1, { p1 = it }, label = { Text("Passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text("At least ${RecoveryKit.MIN_PASSPHRASE} characters. A few random words works well.") },
                )
                OutlinedTextField(
                    p2, { p2 = it }, label = { Text("Type it again") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = mismatch,
                    supportingText = { if (mismatch) Text("Doesn't match") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(p1) }, enabled = !tooShort && p1 == p2) { Text("Next: choose where to save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Onboarding screen: restore from a kit file. */
@Composable
fun RestoreKitScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { PodRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    var fileText by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            if (text == null || text.length > 200_000) throw java.io.IOException("Not a recovery kit")
            fileText = text
            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "kit"
            error = null
        } catch (e: Exception) {
            error = "Couldn't read that file"
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        PodTopBar("Restore from recovery kit", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Hint("Use this on a new or reset phone to come back as the same family member (or admin). If your old phone still works, stop using Pod on it after restoring. A kit is meant for one phone at a time.")
            if (fileText == null) {
                PrimaryButton("Choose kit file", onClick = { picker.launch(arrayOf("*/*")) })
            } else {
                Banner("File: $fileName", PodColors.Good)
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Passphrase") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                error?.let { Banner(it, PodColors.Danger) }
                PrimaryButton(
                    "Restore", enabled = pass.isNotEmpty(), loading = busy,
                    onClick = {
                        busy = true; error = null
                        val text = fileText ?: return@PrimaryButton
                        scope.launch {
                            repo.restoreFromKit(text, pass).onFailure { error = it.message }
                            busy = false
                        }
                    },
                )
                SecondaryButton("Choose a different file", onClick = { picker.launch(arrayOf("*/*")) })
            }
            if (fileText == null) error?.let { Banner(it, PodColors.Danger) }
        }
    }
}
