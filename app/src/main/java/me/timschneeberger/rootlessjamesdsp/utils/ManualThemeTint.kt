package me.timschneeberger.rootlessjamesdsp.utils

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R

/**
 * Paints hand-picked theme colours onto the views themselves.
 *
 * Android has no way to give a theme arbitrary colour values at runtime - theme
 * attributes come from compiled resources - so the generated-palette path can
 * only ever be seeded with one colour. That is fine when every role is derived,
 * but it means a hand-set Secondary, Surface or Background has nowhere to go.
 * This walks the view tree instead and applies those colours directly.
 *
 * It runs only while manual mode is on, so the default derived path is
 * untouched and switching manual mode off restores normal theming completely.
 */
object ManualThemeTint {

    fun apply(activity: Activity) {
        val theme = CustomThemeStore.active(activity) ?: return
        if (!theme.manual || theme.colors.isEmpty()) return

        val primary = theme.colors["Primary"]
        val surface = theme.colors["Surface"]
        val background = theme.colors["Background"]

        background?.let {
            activity.window.setBackgroundDrawable(ColorDrawable(it))
        }

        val root = activity.window.decorView
        tintTree(root, primary, surface, background)

        // Preference rows are inflated during layout and recycled as the list
        // scrolls, so a single pass would miss most of the app. Views already
        // handled are tagged, which keeps repeat passes cheap and stops the
        // re-tint from bouncing layout back and forth.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            tintTree(root, primary, surface, background)
        }
    }

    private fun tintTree(view: View, primary: Int?, surface: Int?, background: Int?) {
        if (view.getTag(R.id.manual_tint_done) == null) {
            tintView(view, primary, surface, background)
            view.setTag(R.id.manual_tint_done, true)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                tintTree(view.getChildAt(i), primary, surface, background)
            }
        }
    }

    private fun tintView(view: View, primary: Int?, surface: Int?, background: Int?) {
        val accent = primary?.let { ColorStateList.valueOf(it) }
        when (view) {
            is MaterialCardView -> surface?.let { view.setCardBackgroundColor(it) }
            is AppBarLayout -> background?.let { view.setBackgroundColor(it) }
            is MaterialToolbar -> background?.let { view.setBackgroundColor(it) }
            is Slider -> accent?.let {
                view.thumbTintList = it
                view.trackActiveTintList = it
            }
            is CompoundButton -> accent?.let {
                // Covers switches, checkboxes and radio buttons
                view.buttonTintList = it
                runCatching {
                    val sw = view as? com.google.android.material.materialswitch.MaterialSwitch
                    sw?.thumbTintList = it
                    sw?.trackTintList = ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(primary, 120)
                    )
                }
            }
            is TextView -> {
                // Keep text readable against a hand-set surface: pick black or
                // white by luminance and preserve the original relative alpha,
                // so titles stay stronger than summaries.
                val base = surface ?: background ?: return
                val onColor = if (ColorUtils.calculateLuminance(base) > 0.5) Color.BLACK
                else Color.WHITE
                val alpha = Color.alpha(view.currentTextColor)
                view.setTextColor(ColorUtils.setAlphaComponent(onColor, alpha))
            }
        }
    }
}
