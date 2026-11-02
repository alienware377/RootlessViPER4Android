package me.timschneeberger.rootlessjamesdsp.utils.github

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import java.io.File

/**
 * The sources the user added, kept in a preferences file of their own.
 *
 * They used to live in dsp_filelibrary, and every dsp_* file is copied
 * wholesale into a preset and back out of it. So loading a preset, or a
 * device profile switching on its own, replaced the user's sources with
 * whatever the preset happened to hold (usually none). Now presets never
 * carry them, and a list found in an older preset is merged in rather than
 * replacing anything.
 *
 * Merging alone brought back sources the user had removed, whenever an
 * older preset that still listed them was loaded. So a removal is
 * remembered, and a merged list never brings that source back; only adding
 * it again by hand does.
 */
object LibrarySources {
    private const val KEY = "filelib_sources"
    /** Keys of the sources the user removed, so an older list cannot bring them back. */
    private const val KEY_REMOVED = "filelib_sources_removed"

    /** The built-in community pack carries both the DDC and the IRS collections. */
    fun builtIn() = listOf(
        Source("programminghoch10/ViPER4AndroidRepackaged", "main",
            "ViPER4Android community pack", builtIn = true)
    )

    fun load(ctx: Context): MutableList<Source> {
        absorbLegacy(ctx)
        return SourceCodec.decode(prefs(ctx).getString(KEY, null)).toMutableList()
    }

    fun save(ctx: Context, sources: List<Source>) {
        prefs(ctx).edit().putString(KEY, SourceCodec.encode(sources)).commit()
    }

    /** Adds [source] unless it is already there. @return false if it was already there */
    fun add(ctx: Context, source: Source): Boolean {
        val list = load(ctx)
        // Added by hand, so an earlier removal no longer stands
        val removed = removed(ctx)
        if (removed.remove(source.key)) saveRemoved(ctx, removed)
        if (list.any { it.key == source.key }) return false
        list += source
        save(ctx, list)
        return true
    }

    fun remove(ctx: Context, source: Source) {
        val removed = removed(ctx)
        if (removed.add(source.key)) saveRemoved(ctx, removed)
        save(ctx, load(ctx).filterNot { it.key == source.key })
    }

    private fun removed(ctx: Context): MutableSet<String> =
        prefs(ctx).getStringSet(KEY_REMOVED, null)?.toHashSet() ?: HashSet()

    private fun saveRemoved(ctx: Context, keys: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_REMOVED, HashSet(keys)).commit()
    }

    /**
     * Moves a list left in the old places into this file: the preset
     * namespace (from before this change, or from a preset just loaded) and
     * the app preferences (from before that). Merged, never replaced, and
     * never bringing back a source the user removed.
     */
    fun absorbLegacy(ctx: Context) {
        @Suppress("DEPRECATION")
        val olds = listOf(
            ctx.getSharedPreferences(Constants.PREF_FILELIBRARY, Context.MODE_MULTI_PROCESS),
            ctx.getSharedPreferences(Constants.PREF_APP, Context.MODE_MULTI_PROCESS),
        )
        val found = olds.filter { it.contains(KEY) }
        if (found.isEmpty()) return
        val removed = removed(ctx)
        var merged = SourceCodec.decode(prefs(ctx).getString(KEY, null))
        found.forEach { merged = SourceCodec.merge(merged, SourceCodec.decode(it.getString(KEY, null)), removed) }
        save(ctx, merged)
        found.forEach { it.edit().remove(KEY).commit() }
    }

    @Suppress("DEPRECATION")
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(Constants.PREF_FILELIBRARY_SOURCES, Context.MODE_MULTI_PROCESS)

    /** Where cached GitHub replies go. */
    fun cacheDir(ctx: Context) = File(ctx.cacheDir, "github")
}
