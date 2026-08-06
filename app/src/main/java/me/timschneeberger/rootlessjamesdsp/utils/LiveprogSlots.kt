package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R

/**
 * Maps the multi-selection made in the first Liveprog card's file picker onto
 * the four engine slots.
 *
 * Slots keep their identity: deselecting the script in slot 2 leaves slot 2
 * empty rather than shifting slot 3 up, because the processing chain order is
 * user-controlled and silently moving a script would change how it sounds.
 */
object LiveprogSlots {
    const val COUNT = 4

    private fun namespace(slot: Int) = when (slot) {
        0 -> Constants.PREF_LIVEPROG
        1 -> Constants.PREF_LIVEPROG2
        2 -> Constants.PREF_LIVEPROG3
        else -> Constants.PREF_LIVEPROG4
    }

    private fun fileKey(ctx: Context, slot: Int) = ctx.getString(
        when (slot) {
            0 -> R.string.key_liveprog_file
            1 -> R.string.key_liveprog2_file
            2 -> R.string.key_liveprog3_file
            else -> R.string.key_liveprog4_file
        }
    )

    private fun enableKey(ctx: Context, slot: Int) = ctx.getString(
        when (slot) {
            0 -> R.string.key_liveprog_enable
            1 -> R.string.key_liveprog2_enable
            2 -> R.string.key_liveprog3_enable
            else -> R.string.key_liveprog4_enable
        }
    )

    /** Current script value for each slot; empty string means the slot is free. */
    fun read(ctx: Context): Array<String> = Array(COUNT) { slot ->
        ctx.getSharedPreferences(namespace(slot), Context.MODE_MULTI_PROCESS)
            .getString(fileKey(ctx, slot), "") ?: ""
    }

    fun isOccupied(ctx: Context, slot: Int) = read(ctx)[slot].isNotBlank()

    /** Assigns [value] to [slot], enabling or disabling that slot to match. */
    fun write(ctx: Context, slot: Int, value: String) {
        val prefs = ctx.getSharedPreferences(namespace(slot), Context.MODE_MULTI_PROCESS)
        prefs.edit()
            .putString(fileKey(ctx, slot), value)
            .putBoolean(enableKey(ctx, slot), value.isNotBlank())
            .apply()
    }

    /**
     * Toggles a script. Returns the slot it now occupies, or -1 if it was
     * removed or there was no free slot left.
     */
    fun toggle(ctx: Context, value: String): Int {
        val slots = read(ctx)
        val existing = slots.indexOfFirst { it == value }
        if (existing >= 0) {
            write(ctx, existing, "")
            return -1
        }
        // Lowest free slot, so gaps left by removals get reused
        val free = slots.indexOfFirst { it.isBlank() }
        if (free < 0) return -1
        write(ctx, free, value)
        return free
    }

    /** 1-based slot number for a script, or null when it isn't selected. */
    fun slotNumberOf(ctx: Context, value: String): Int? {
        val idx = read(ctx).indexOfFirst { it == value }
        return if (idx < 0) null else idx + 1
    }

    /** True when the user has opted into selecting several scripts at once. */
    fun isMultiMode(ctx: Context): Boolean =
        ctx.getSharedPreferences(Constants.PREF_APP, Context.MODE_MULTI_PROCESS)
            .getBoolean(KEY_MULTI_MODE, false)

    fun setMultiMode(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(Constants.PREF_APP, Context.MODE_MULTI_PROCESS)
            .edit().putBoolean(KEY_MULTI_MODE, enabled).apply()
        if (!enabled) {
            // Collapsing back to a single script: keep slot 1, clear the rest
            for (slot in 1 until COUNT) write(ctx, slot, "")
        }
    }

    private const val KEY_MULTI_MODE = "liveprog_multi_select"
}
