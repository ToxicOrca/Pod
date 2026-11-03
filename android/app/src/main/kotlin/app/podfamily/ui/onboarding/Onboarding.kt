package app.podfamily.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import app.podfamily.data.InviteLink
import app.podfamily.data.PodRepository
import app.podfamily.ui.components.Banner
import app.podfamily.ui.components.Hint
import app.podfamily.ui.components.PodTopBar
import app.podfamily.ui.components.PrimaryButton
import app.podfamily.ui.components.SecondaryButton
import app.podfamily.ui.theme.PodColors
import kotlinx.coroutines.launch

private enum class Step { Welcome, Create, Join, Restore }

@Composable
fun OnboardingFlow() {
    var step by rememberSaveable { mutableStateOf(Step.Welcome) }
    BackHandler(enabled = step != Step.Welcome) { step = Step.Welcome }
    when (step) {
        Step.Welcome -> WelcomeScreen(onCreate = { step = Step.Create }, onJoin = { step = Step.Join }, onRestore = { step = Step.Restore })
        Step.Create -> CreateFamilyScreen(onBack = { step = Step.Welcome })
        Step.Join -> JoinFamilyScreen(onBack = { step = Step.Welcome })
        Step.Restore -> app.podfamily.ui.RestoreKitScreen(onBack = { step = Step.Welcome })
    }
}

@Composable
private fun WelcomeScreen(onCreate: () -> Unit, onJoin: () -> Unit, onRestore: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(24.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Groups, null, tint = PodColors.Accent, modifier = Modifier.size(84.dp))
            Spacer(Modifier.height(16.dp))
            Text("Pod", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(10.dp))
            Text(
                "See where your family is.\nNobody else can - not even the server.",
                style = MaterialTheme.typography.bodyLarge, color = PodColors.TextDim, textAlign = TextAlign.Center,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Join my family", onJoin)
            SecondaryButton("Start a new family", onCreate)
            androidx.compose.material3.TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                Text("New phone? Restore from recovery kit")
            }
            Hint("Starting a family needs your Pod server's address and setup code. Joining just needs a QR code from the family admin.")
        }
    }
}

@Composable
private fun CreateFamilyScreen(onBack: () -> Unit) {
    val repo = PodRepository.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        PodTopBar("Start a family", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Hint("You'll be the family admin: you invite people and can remove them.")
            OutlinedTextField(
                server, { server = it }, label = { Text("Server address") }, placeholder = { Text("pod.example.com") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                code, { code = it }, label = { Text("Setup code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                supportingText = { Text("POD_SETUP_TOKEN from your server's .env file") },
            )
            OutlinedTextField(
                name, { name = it.take(40) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            )
            error?.let { Banner(it, PodColors.Danger) }
            Spacer(Modifier.height(4.dp))
            PrimaryButton(
                "Create family",
                enabled = server.isNotBlank() && code.isNotBlank() && name.isNotBlank(),
                loading = busy,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        repo.createFamily(server, code, name).onFailure { error = it.message }
                        busy = false
                    }
                },
            )
        }
    }
}

@Composable
private fun JoinFamilyScreen(onBack: () -> Unit) {
    val repo = PodRepository.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<InviteLink?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val parsed = InviteLink.parse(text)
        if (parsed == null) error = "That QR code isn't a Pod invite." else { link = parsed; error = null }
    }
    fun scan() = scanner.launch(
        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the QR code on the admin's phone")
            .setBeepEnabled(false).setOrientationLocked(false)
    )

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        PodTopBar("Join your family", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val l = link
            if (l == null) {
                Hint("On the family admin's phone, open Pod > Invite. Then scan the QR code it shows.")
                Hint("Invites only work in person - that's what keeps strangers out of your family.")
                error?.let { Banner(it, PodColors.Danger) }
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Scan QR code", ::scan)
            } else {
                Banner("Invite for ${l.serverUrl.removePrefix("https://")}", PodColors.Good)
                OutlinedTextField(
                    name, { name = it.take(40) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    supportingText = { Text("This is how your family will see you") },
                )
                error?.let { Banner(it, PodColors.Danger) }
                PrimaryButton(
                    "Join family", enabled = name.isNotBlank(), loading = busy,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            repo.joinFamily(l, name).onFailure { error = it.message }
                            busy = false
                        }
                    },
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SecondaryButton("Scan a different code", ::scan)
                }
            }
        }
    }
}
