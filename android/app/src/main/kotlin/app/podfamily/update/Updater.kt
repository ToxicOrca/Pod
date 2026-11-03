package app.podfamily.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.podfamily.BuildConfig
import app.podfamily.data.PodJson
import app.podfamily.data.PodRepository
import app.podfamily.net.Http
import app.podfamily.system.DiagLog
import app.podfamily.system.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String = "",
    val apk: String,
    val sha256: String,
)

/**
 * In-app updates from your own server (/app/latest.json + signed APK).
 * Updating keeps everything: Android installs the new APK over the old one only
 * if it's signed with the same release key, and all data and permissions stay.
 */
object Updater {
    private const val PREFS = "updater"
    private const val CHECK_EVERY_MS = 6 * 3_600_000L
    /** When someone opens Pod themselves, check again if it's been this long (one tiny request). */
    const val CHECK_ON_OPEN_MS = 15 * 60_000L
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private val APK_NAME = Regex("^pod-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk$")

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    /** Download progress 0..1 while downloading, null otherwise. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    /** Debug builds are signed differently and update from CI, not from the server. */
    val enabled: Boolean get() = !BuildConfig.DEBUG

    suspend fun check(context: Context, force: Boolean = false, minIntervalMs: Long = CHECK_EVERY_MS): UpdateInfo? {
        if (!enabled) return null
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("lastCheck", 0) < minIntervalMs) return _available.value
        val server = PodRepository.get(ctx).state.value.family?.serverUrl ?: return null
        val info = withContext(Dispatchers.IO) {
            try {
                Http.client.newCall(Request.Builder().url("$server/app/latest.json").build()).execute().use { r ->
                    if (!r.isSuccessful) null else PodJson.decodeFromString<UpdateInfo>(r.body?.string().orEmpty())
                }
            } catch (e: Exception) {
                DiagLog.w("Update check failed: ${e.javaClass.simpleName}")
                null
            }
        }
        prefs.edit().putLong("lastCheck", now).apply()
        val newer = info?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        _available.value = newer
        if (newer != null && prefs.getInt("notified", 0) < newer.versionCode) {
            prefs.edit().putInt("notified", newer.versionCode).apply()
            DiagLog.i("Update available: ${newer.versionName}")
            Notifications.show(ctx, Notifications.CH_FAMILY, "Pod update available", "Version ${newer.versionName} is ready. Open Pod and tap Update.")
        }
        return newer
    }

    /** Returns null if [apk] is a newer build of THIS app signed with THIS app's key, else the reason it isn't. */
    @Suppress("DEPRECATION")
    private fun verifyApk(ctx: Context, apk: File, info: UpdateInfo): String? {
        val pm = ctx.packageManager
        val modern = Build.VERSION.SDK_INT >= 28
        val flags = if (modern) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return "not a valid app file"
        if (archive.packageName != ctx.packageName) return "it's a different app"
        val installed = try { pm.getPackageInfo(ctx.packageName, flags) } catch (e: Exception) { return "can't read this app's signature" }
        val newCode = versionCodeOf(archive)
        if (newCode <= versionCodeOf(installed)) return "it isn't newer than this version"
        if (newCode != info.versionCode.toLong()) return "its version doesn't match the announcement"
        var mine = signerHashes(installed)
        var theirs = signerHashes(archive)
        if (modern && (mine.isEmpty() || theirs.isEmpty())) {
            // A few devices don't fill in signing info for archives; fall back to the older API.
            mine = signerHashes(pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES))
            theirs = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)?.let { signerHashes(it) } ?: emptySet()
        }
        if (mine.isEmpty() || theirs.isEmpty() || mine != theirs) return "it isn't signed with this app's key"
        return null
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signerHashes(p: PackageInfo): Set<String> {
        val sigs: Array<android.content.pm.Signature>? = if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) {
            val si = p.signingInfo!!
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            p.signatures
        }
        if (sigs.isNullOrEmpty()) return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { sig -> md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Android requires the user to allow Pod to install apps, once. */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /** Downloads, verifies, and hands the APK to Android's installer (one "Update" tap). */
    suspend fun downloadAndInstall(context: Context): Result<Unit> = runCatching {
        val ctx = context.applicationContext
        val info = _available.value ?: throw IllegalStateException("No update available")
        if (!APK_NAME.matches(info.apk)) throw IOException("The update information from the server looks wrong.")
        val server = PodRepository.get(ctx).state.value.family?.serverUrl ?: throw IllegalStateException("Not set up")
        val file = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, "pod-update.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            _progress.value = 0f
            try {
                Http.client.newCall(Request.Builder().url("$server/app/${info.apk}").build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
                    val body = r.body ?: throw IOException("Empty download")
                    val total = body.contentLength().takeIf { it > 0 }
                    if (total != null && total > MAX_APK_BYTES) throw IOException("The update is unexpectedly large.")
                    var done = 0L
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                if (done > MAX_APK_BYTES) throw IOException("The update is unexpectedly large.")
                                if (total != null) _progress.value = done.toFloat() / total
                            }
                        }
                    }
                }
            } finally {
                _progress.value = null
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(info.sha256, ignoreCase = true)) {
                out.delete()
                throw IOException("The download was damaged. Please try again.")
            }
            // The hash only proves the file matches latest.json, which comes from the same server.
            // Before offering it, make sure it really is a newer Pod signed with this app's own key,
            // so a hacked server can never use this prompt to install some other app.
            verifyApk(ctx, out, info)?.let { problem ->
                out.delete()
                DiagLog.e("SECURITY: refused update ${info.versionName}: $problem")
                throw IOException("This update couldn't be verified, so it wasn't installed. ($problem)")
            }
            out
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        DiagLog.i("Launching installer for ${info.versionName}")
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
