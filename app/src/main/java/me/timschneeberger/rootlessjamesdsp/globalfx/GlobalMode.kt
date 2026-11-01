package me.timschneeberger.rootlessjamesdsp.globalfx

import android.content.Context
import android.media.audiofx.AudioEffect
import android.os.SystemClock
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import timber.log.Timber
import java.util.ArrayDeque

/**
 * Global mode's switch and settings, read cheaply.
 *
 * isOn is consulted from card binding and from the receivers that run for
 * every user, so it is cached like V4aMode and costs one boolean once read.
 * Rootless only: the rooted build already processes every app through its
 * own engine and the plugin build has no service of its own.
 */
object GlobalMode {
    enum class Target { ALL, APPS }

    @Volatile private var cached: Boolean? = null
    @Volatile private var available: Boolean? = null

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean {
        if (!isRootless()) return false
        return cached ?: prefs(ctx)
            .getBoolean(ctx.getString(R.string.key_global_mode), false)
            .also { cached = it }
    }

    /** Called when the switch changes, so the next read picks it up. */
    fun invalidate() { cached = null }

    fun setOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(ctx.getString(R.string.key_global_mode), on).apply()
        cached = on
    }

    /**
     * Whether the user left global mode running. Players announcing themselves
     * and the boot receiver only start the service when this is set, so the
     * power button's "off" is respected. Internal, not a setting.
     */
    fun isWanted(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_WANTED, false)

    fun setWanted(ctx: Context, wanted: Boolean) {
        if (isWanted(ctx) != wanted) prefs(ctx).edit().putBoolean(KEY_WANTED, wanted).apply()
    }

    private const val KEY_WANTED = "global_mode_wanted"

    fun target(ctx: Context): Target =
        if (prefs(ctx).getString(ctx.getString(R.string.key_global_target), "all") == "apps") Target.APPS
        else Target.ALL

    fun frameMs(ctx: Context): Float =
        prefs(ctx).getString(ctx.getString(R.string.key_global_precision), "40")
            ?.toFloatOrNull()?.takeIf { it in 5f..200f } ?: 40f

    fun autoPreamp(ctx: Context): Boolean =
        prefs(ctx).getBoolean(ctx.getString(R.string.key_global_auto_preamp), true)

    /** The Android effect global mode needs, if this phone lists one. */
    fun dynamicsDescriptor(): AudioEffect.Descriptor? = try {
        AudioEffect.queryEffects()?.firstOrNull {
            it.type == AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING
        }
    } catch (ex: Throwable) {
        Timber.w(ex, "queryEffects failed")
        null
    }

    /** Whether to offer the switch at all. Asked once per process. */
    fun isAvailable(): Boolean {
        if (!isRootless()) return false
        return available ?: (dynamicsDescriptor() != null).also { available = it }
    }

    // ---- what the check screen shows about broadcasts -----------------------

    data class BroadcastEntry(val atMs: Long, val open: Boolean, val pkg: String?, val session: Int, val contentType: Int)

    private val broadcasts = ArrayDeque<BroadcastEntry>()

    /**
     * Remembers the last players that announced themselves. Called by the
     * session receiver only while global mode is on, so it costs nobody else
     * anything, and kept in memory only: it is for the check screen, not a log.
     */
    fun recordBroadcast(open: Boolean, pkg: String?, session: Int, contentType: Int) {
        synchronized(broadcasts) {
            broadcasts.addFirst(BroadcastEntry(SystemClock.elapsedRealtime(), open, pkg, session, contentType))
            while (broadcasts.size > 20) broadcasts.removeLast()
        }
    }

    fun recentBroadcasts(): List<BroadcastEntry> = synchronized(broadcasts) { broadcasts.toList() }

    /** Last time a background start of the service was refused, for the check screen. */
    @Volatile var lastStartRefusedAtMs: Long = 0L
}
