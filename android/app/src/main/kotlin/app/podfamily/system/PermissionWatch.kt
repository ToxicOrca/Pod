package app.podfamily.system

import android.content.Context
import app.podfamily.data.PodRepository

/**
 * Android can take back "Allow all the time" (its periodic reminder makes that one tap).
 * Without it Pod can't share, so instead of stopping silently: tell this phone's owner,
 * and tell the family once. Runs from the watchdog (every 15 min) and whenever Pod opens.
 */
object PermissionWatch {
    suspend fun check(ctx: Context) {
        val repo = PodRepository.get(ctx)
        // Not during first setup: the permission screens haven't been completed yet.
        if (!repo.isInFamily || !repo.state.value.permissionsSetupDone) return
        if (Permissions.isApproximateOnly(ctx)) {
            Notifications.show(
                ctx, Notifications.CH_FAMILY, "Pod only has approximate location",
                "Your family sees you about 2 km off. Tap, then Permissions > Location > turn on \"Use precise location\".",
                Notifications.ID_APPROXIMATE, onlyAlertOnce = true,
            )
        } else {
            Notifications.cancel(ctx, Notifications.ID_APPROXIMATE)
        }
        if (!Permissions.hasBackgroundLocation(ctx)) {
            Notifications.show(
                ctx, Notifications.CH_FAMILY, "Pod stopped sharing your location",
                "Location permission was turned off, so your family can't see you. Tap to fix it.",
                Notifications.ID_PERMISSION, onlyAlertOnce = true,
            )
            repo.onPermissionLost()
        } else {
            Notifications.cancel(ctx, Notifications.ID_PERMISSION)
            repo.onPermissionRestored()
        }
    }
}
