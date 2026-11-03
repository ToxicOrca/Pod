package app.podfamily.ui.map

import app.podfamily.net.Http
import app.podfamily.system.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Dark map themes. The style files live on your server at /map/themes/<id>.json
 * (generated from Protomaps; see deploy/map/themes/README.md). All are dark.
 */
object MapThemes {
    data class Theme(val id: String, val name: String, val description: String)

    val all = listOf(
        Theme("midnight", "Midnight", "Balanced dark with clear streets and labels (default)"),
        Theme("contrast", "High contrast", "Bright roads and white labels. Easiest to read, good in sunlight"),
        Theme("navy", "Navy", "Blue-tinted dark"),
        Theme("oled", "Pure black", "True black. Saves battery on OLED screens"),
        Theme("classic", "Classic", "The original, very subtle dark style"),
    )

    /** Returns the style JSON with the server URL filled in, or null if the server doesn't have it. */
    suspend fun load(serverUrl: String, id: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$serverUrl/map/themes/$id.json").build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    DiagLog.w("Map theme '$id' not on server (HTTP ${r.code}) - using default style")
                    null
                } else {
                    r.body?.string()?.replace("__POD_PUBLIC_URL__", serverUrl)
                }
            }
        } catch (e: Exception) {
            DiagLog.w("Map theme '$id' failed to load: ${e.javaClass.simpleName}")
            null
        }
    }
}
