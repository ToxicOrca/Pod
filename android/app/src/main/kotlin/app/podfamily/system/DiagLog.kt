package app.podfamily.system

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only diagnostics log, viewable in Settings > Diagnostics.
 * There are no third-party crash reporters or analytics in Pod; when something
 * goes wrong, this log is how you find out why. It never records locations,
 * names, or keys.
 */
object DiagLog {
    private const val MAX_LINES = 600
    private const val TAG = "Pod"
    private var file: File? = null
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        val f = File(context.filesDir, "diag.log")
        file = f
        synchronized(lines) {
            if (f.exists()) runCatching { f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) } }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            e("CRASH in ${t.name}: ${ex.stackTraceToString().take(4000)}")
            previous?.uncaughtException(t, ex)
        }
    }

    fun i(msg: String) = add("I", msg)
    fun w(msg: String) = add("W", msg)
    fun e(msg: String) = add("E", msg)

    private fun add(level: String, msg: String) {
        val line = "${fmt.format(Date())} $level $msg"
        when (level) {
            "E" -> Log.e(TAG, msg)
            "W" -> Log.w(TAG, msg)
            else -> Log.i(TAG, msg)
        }
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            runCatching { file?.writeText(lines.joinToString("\n")) }
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) {
        lines.clear()
        runCatching { file?.delete() }
    }
}
