package app.podfamily.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.podfamily.MainActivity
import app.podfamily.R

object Notifications {
    const val CH_SERVICE = "service"
    const val CH_ALERTS = "alerts"
    const val CH_SOS = "sos"
    const val CH_FAMILY = "family"
    const val ID_SERVICE = 1
    const val ID_RESTART = 2
    const val ID_PERMISSION = 3
    const val ID_APPROXIMATE = 4
    /**
     * Unique ids even across app restarts (seeded from the clock), so a new alert never
     * replaces an unread one. SOS alerts get a fixed id per sender: see [sosId].
     */
    private val nextId = java.util.concurrent.atomic.AtomicInteger(1000 + ((System.currentTimeMillis() / 1000) % 100_000_000).toInt())

    private fun newId(): Int {
        val id = nextId.getAndIncrement()
        if (id >= 400_000_000) nextId.set(1000)
        return id
    }

    /** One SOS notification per sender: a repeat SOS updates it instead of piling up, and nothing else can replace it. */
    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    /** Has the user allowed the SOS channel to override Do Not Disturb? (Apps can't set this themselves.) */
    fun sosBypassesDnd(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.getNotificationChannel(CH_SOS)?.canBypassDnd() == true
    }

    fun sosChannelSettingsIntent(context: Context): android.content.Intent =
        android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CH_SOS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    fun sosId(senderId: String): Int = 500_000_000 + (senderId.hashCode() and 0x0FFFFFFF)

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_SERVICE, "Location sharing status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Pod is sharing your location. Required by Android."
                    setShowBadge(false)
                },
                NotificationChannel(CH_ALERTS, "Arrivals and departures", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When family members arrive at or leave a place"
                },
                NotificationChannel(CH_SOS, "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When a family member sends an SOS"
                    enableVibration(true)
                    setBypassDnd(true)
                },
                NotificationChannel(CH_FAMILY, "Family updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Members joining, leaving, or pausing sharing"
                },
            )
        )
    }

    /** Extra on the launch intent: open the map centered on this family member. */
    const val EXTRA_FOCUS_MEMBER = "focus_member"

    fun openAppIntent(context: Context, memberId: String? = null): PendingIntent = PendingIntent.getActivity(
        // A different request code per member, so each alert opens on its own person.
        context, memberId?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (memberId != null) putExtra(EXTRA_FOCUS_MEMBER, memberId) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun serviceNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pod")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setColor(0xFF5EC8F2.toInt())
            .setContentIntent(openAppIntent(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(
        context: Context, channel: String, title: String, text: String, id: Int = newId(), memberId: String? = null,
        /** Re-posting the same id updates it silently instead of buzzing again. */
        onlyAlertOnce: Boolean = false,
    ) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setColor(if (channel == CH_SOS) 0xFFFF5A5F.toInt() else 0xFF5EC8F2.toInt())
            .setPriority(if (channel == CH_SOS || channel == CH_ALERTS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (channel == CH_SOS) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent(context, memberId))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            DiagLog.w("notify failed: ${e.message}")
        }
    }
}
