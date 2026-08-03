package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashBreadcrumb {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun mark(context: Context, msg: String) {
        try {
            val f = File(context.filesDir, "breadcrumb.txt")
            if (f.exists() && f.length() > 65536) {
                val tail = f.readLines().takeLast(50).joinToString("\n")
                f.writeText(tail + "\n")
            }
            f.appendText(fmt.format(Date()) + " " + msg + "\n")
        } catch (_: Exception) {}
    }
}
