package app.podfamily

import android.app.Application
import app.podfamily.system.DiagLog
import app.podfamily.system.Notifications
import org.maplibre.android.MapLibre

class PodApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        Notifications.createChannels(this)
        MapLibre.getInstance(this)
        DiagLog.i("App start v${BuildConfig.VERSION_NAME}")
    }
}
