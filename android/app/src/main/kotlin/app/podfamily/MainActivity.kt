package app.podfamily

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.podfamily.update.Updater
import kotlinx.coroutines.launch
import app.podfamily.data.PodRepository
import app.podfamily.system.Permissions
import app.podfamily.system.ServiceController
import app.podfamily.ui.PermissionsScreen
import app.podfamily.ui.components.Hint
import app.podfamily.ui.components.PrimaryButton
import app.podfamily.ui.invite.InviteScreen
import app.podfamily.ui.map.MapScreen
import app.podfamily.ui.onboarding.OnboardingFlow
import app.podfamily.ui.places.PlacesScreen
import app.podfamily.ui.settings.SettingsScreen
import app.podfamily.ui.theme.PodColors
import app.podfamily.ui.theme.PodTheme

/** "Open the map on this person" requests from tapped notifications. */
object FocusRequests {
    val member = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

class MainActivity : ComponentActivity() {
    private fun takeFocusRequest(intent: Intent?) {
        val id = intent?.getStringExtra(app.podfamily.system.Notifications.EXTRA_FOCUS_MEMBER) ?: return
        intent.removeExtra(app.podfamily.system.Notifications.EXTRA_FOCUS_MEMBER)
        FocusRequests.member.value = id
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeFocusRequest(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only for a fresh launch from a notification, not when the screen is recreated.
        if (savedInstanceState == null) takeFocusRequest(intent)
        // Always dark system bars - Pod has no light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { PodTheme { PodRoot() } }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is always a chance to heal a stopped service.
        ServiceController.ensureRunning(this, "app opened")
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            app.podfamily.system.PermissionWatch.check(this@MainActivity)
        }
        // Opening the app: look for an update if we haven't in 15 minutes (background checks stay at 6 h).
        lifecycleScope.launch { Updater.check(this@MainActivity, minIntervalMs = Updater.CHECK_ON_OPEN_MS) }
    }
}

private enum class Screen { Map, Places, Invite, Settings }

@Composable
private fun PodRoot() {
    val ctx = LocalContext.current
    val repo = remember { PodRepository.get(ctx) }
    val state by repo.state.collectAsStateWithLifecycle()
    var permsTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { permsTick++; onPauseOrDispose { } }
    val hasPerms = remember(permsTick) { Permissions.hasBackgroundLocation(ctx) }

    Surface(Modifier.fillMaxSize(), color = PodColors.Background) {
        when {
            state.removed -> RemovedScreen { repo.wipeLocal() }
            state.family == null -> OnboardingFlow()
            // Stay on the guided setup until the user taps Continue - granting
            // "Allow all the time" alone must not skip the notification and battery steps.
            !hasPerms || !state.permissionsSetupDone -> PermissionsScreen(onDone = {
                repo.markPermissionsSetupDone()
                ServiceController.ensureRunning(ctx, "permissions granted")
            })
            else -> MainFlow()
        }
    }
    // Start sharing as soon as we're in a family with permissions.
    LaunchedEffect(state.family, hasPerms) {
        if (state.family != null && hasPerms) ServiceController.ensureRunning(ctx, "ready")
    }
}

@Composable
private fun MainFlow() {
    var screen by rememberSaveable { mutableStateOf(Screen.Map) }
    var focusPlace by remember { mutableStateOf<app.podfamily.data.Place?>(null) }
    val focusMember by FocusRequests.member.collectAsStateWithLifecycle()
    // A tapped notification about someone: go to the map, which then centers on them.
    LaunchedEffect(focusMember) {
        if (focusMember != null) { focusPlace = null; screen = Screen.Map }
    }
    BackHandler(enabled = screen != Screen.Map) { screen = Screen.Map }
    when (screen) {
        Screen.Map -> MapScreen(
            focusPlace = focusPlace,
            focusMemberId = focusMember,
            onFocusMemberDone = { FocusRequests.member.value = null },
            onPlaces = { focusPlace = null; screen = Screen.Places },
            onInvite = { focusPlace = null; screen = Screen.Invite },
            onSettings = { focusPlace = null; screen = Screen.Settings },
        )
        Screen.Places -> PlacesScreen(
            onBack = { screen = Screen.Map },
            onShowOnMap = { p -> focusPlace = p; screen = Screen.Map },
        )
        Screen.Invite -> InviteScreen(onBack = { screen = Screen.Map })
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Map })
    }
}

@Composable
private fun RemovedScreen(onReset: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("You're no longer in this family", style = MaterialTheme.typography.headlineSmall)
        Hint("The family admin removed this phone, or deleted the family. Your location is no longer being shared.")
        PrimaryButton("Set up again", onReset)
    }
}
