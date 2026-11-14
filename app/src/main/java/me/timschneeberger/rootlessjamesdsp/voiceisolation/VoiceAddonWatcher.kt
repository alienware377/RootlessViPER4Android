package me.timschneeberger.rootlessjamesdsp.voiceisolation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PatternMatcher
import androidx.core.content.ContextCompat

/**
 * Calls [onChange] on the main thread whenever the voice isolation add-on is installed, updated,
 * removed, enabled or disabled, so the card and the engine notice without a restart.
 */
internal class VoiceAddonWatcher(private val context: Context, private val onChange: () -> Unit) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = onChange()
    }
    private var registered = false

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
            addDataSchemeSpecificPart(VoiceAddon.PACKAGE, PatternMatcher.PATTERN_LITERAL)
        }
        // Exported so that the system's package broadcasts arrive on every version. Another app
        // sending one can only make the add-on be checked again, which is harmless.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already gone with its context.
        }
    }
}
