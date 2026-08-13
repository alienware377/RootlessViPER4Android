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
        val flatten = V4aIconColors.isClassicLayout(activity)
        val theme = CustomThemeStore.active(activity)
        val manual = theme?.manual == true && theme.colors.isNotEmpty()
        // Flattening cards is a layout choice, so it runs even when the colours
        // are being generated rather than hand-set.
        if (!manual && !flatten) return

        val primary = theme?.colors?.get("Primary")?.takeIf { manual }
        val secondary = theme?.colors?.get("Secondary")?.takeIf { manual }
        val tertiary = theme?.colors?.get("Tertiary")?.takeIf { manual }
        val surface = theme?.colors?.get("Surface")?.takeIf { manual }
        val background = theme?.colors?.get("Background")?.takeIf { manual }

        background?.let {
            activity.window.setBackgroundDrawable(ColorDrawable(it))
        }

        val roles = Roles(primary, secondary, tertiary, surface, background, flatten)
        val root = activity.window.decorView
        tintTree(root, roles)

        // Preference rows are inflated during layout and recycled as the list
        // scrolls, so a single pass would miss most of the app. Views already
        // handled are tagged, which keeps repeat passes cheap and stops the
        // re-tint from bouncing layout back and forth.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            tintTree(root, roles)
        }
    }

    private data class Roles(
        val primary: Int?, val secondary: Int?, val tertiary: Int?,
        val surface: Int?, val background: Int?, val flatten: Boolean,
    )

    private fun tintTree(view: View, roles: Roles) {
        if (view.getTag(R.id.manual_tint_done) == null) {
            tintView(view, roles)
            view.setTag(R.id.manual_tint_done, true)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                tintTree(view.getChildAt(i), roles)
            }
        }
    }

    private fun tintView(view: View, roles: Roles) {
        val accent = roles.primary?.let { ColorStateList.valueOf(it) }
        when (view) {
            is MaterialCardView -> {
                roles.surface?.let { view.setCardBackgroundColor(it) }
                if (roles.flatten) {
                    // Structure only: the card keeps whatever colour it has, so
                    // a custom theme's surface stays distinct from background.
                    view.radius = 0f
                    view.cardElevation = 0f
                    view.strokeWidth = 0
                }
            }
            is AppBarLayout -> roles.background?.let { view.setBackgroundColor(it) }
            is MaterialToolbar -> roles.background?.let { view.setBackgroundColor(it) }
            is com.google.android.material.floatingactionbutton.FloatingActionButton ->
                roles.secondary?.let { view.backgroundTintList = ColorStateList.valueOf(it) }
            is com.google.android.material.button.MaterialButton ->
                roles.secondary?.let { c ->
                    // Filled buttons take the fill; outlined ones only the text
                    // and stroke, so they don't turn into solid blocks.
                    if (view.backgroundTintList != null && view.strokeWidth == 0)
                        view.backgroundTintList = ColorStateList.valueOf(c)
                    else {
                        view.setTextColor(c)
                        view.strokeColor = ColorStateList.valueOf(c)
                    }
                }
            is Slider -> {
                accent?.let {
                    view.thumbTintList = it
                    view.trackActiveTintList = it
                }
                roles.tertiary?.let {
                    view.trackInactiveTintList =
                        ColorStateList.valueOf(ColorUtils.setAlphaComponent(it, 90))
                }
            }
            is CompoundButton -> accent?.let {
                // Covers switches, checkboxes and radio buttons
                view.buttonTintList = it
                runCatching {
                    val sw = view as? com.google.android.material.materialswitch.MaterialSwitch
                    sw?.thumbTintList = it
                    sw?.trackTintList = ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(roles.primary!!, 120)
                    )
                }
            }
            is TextView -> {
                // Keep text readable against a hand-set surface: pick black or
                // white by luminance and preserve the original relative alpha,
                // so titles stay stronger than summaries.
                val base = roles.surface ?: roles.background ?: return
                val onColor = if (ColorUtils.calculateLuminance(base) > 0.5) Color.BLACK
                else Color.WHITE
                val alpha = Color.alpha(view.currentTextColor)
                view.setTextColor(ColorUtils.setAlphaComponent(onColor, alpha))
            }
        }
    }
}
