package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.util.TypedValue
import me.timschneeberger.rootlessjamesdsp.R

/**
 * The ViPER4Android classic theme tints every effect icon a single purple,
 * matching the original app's one-accent look, rather than the per-effect
 * colours tried earlier.
 */
object V4aIconColors {
    /** True when the active theme asks for purple effect icons. */
    fun isEnabled(context: Context): Boolean {
        val tv = TypedValue()
        return context.theme.resolveAttribute(R.attr.v4aColorfulIcons, tv, true) && tv.data != 0
    }

    fun tint(context: Context): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
        return tv.data
    }
}
