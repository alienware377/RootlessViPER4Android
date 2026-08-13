package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-created themes. Each is a seed colour plus a couple of switches; the
 * full palette is derived from the seed at apply time rather than stored, so a
 * theme stays coherent instead of being a bag of unrelated colours.
 */
object CustomThemeStore {

    /** Roles the user can set by hand, in the order they appear in the preview. */
    val ROLES = listOf("Primary", "Secondary", "Tertiary", "Surface", "Bars", "Background")

    data class CustomTheme(
        val id: String,
        var name: String,
        var seed: Int,
        var dark: Boolean = true,
        var amoled: Boolean = false,
        /** When true, [colors] is used verbatim instead of deriving from [seed]. */
        var manual: Boolean = false,
        var colors: MutableMap<String, Int> = mutableMapOf(),
    )

    private const val KEY_THEMES = "custom_themes"
    private const val KEY_ACTIVE = "custom_theme_active"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(Constants.PREF_APP, Context.MODE_MULTI_PROCESS)

    fun all(ctx: Context): MutableList<CustomTheme> {
        val raw = prefs(ctx).getString(KEY_THEMES, null) ?: return mutableListOf()
        val out = mutableListOf<CustomTheme>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val colors = mutableMapOf<String, Int>()
                o.optJSONObject("colors")?.let { c ->
                    ROLES.forEach { role -> if (c.has(role)) colors[role] = c.getInt(role) }
                }
                out.add(
                    CustomTheme(
                        o.getString("id"),
                        o.getString("name"),
                        o.getInt("seed"),
                        o.optBoolean("dark", true),
                        o.optBoolean("amoled", false),
                        o.optBoolean("manual", false),
                        colors,
                    )
                )
            }
        }
        return out
    }

    fun save(ctx: Context, themes: List<CustomTheme>) {
        val arr = JSONArray()
        themes.forEach {
            val colors = JSONObject()
            it.colors.forEach { (role, color) -> colors.put(role, color) }
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("seed", it.seed)
                    .put("dark", it.dark)
                    .put("amoled", it.amoled)
                    .put("manual", it.manual)
                    .put("colors", colors)
            )
        }
        prefs(ctx).edit().putString(KEY_THEMES, arr.toString()).apply()
    }

    fun upsert(ctx: Context, theme: CustomTheme) {
        val list = all(ctx)
        val i = list.indexOfFirst { it.id == theme.id }
        if (i >= 0) list[i] = theme else list.add(theme)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        save(ctx, all(ctx).filterNot { it.id == id })
        if (activeId(ctx) == id) setActive(ctx, null)
    }

    fun activeId(ctx: Context): String? = prefs(ctx).getString(KEY_ACTIVE, null)

    fun setActive(ctx: Context, id: String?) {
        prefs(ctx).edit().apply {
            if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id)
        }.apply()
    }

    fun active(ctx: Context): CustomTheme? {
        val id = activeId(ctx) ?: return null
        return all(ctx).firstOrNull { it.id == id }
    }

    fun newId(): String = "theme_" + System.currentTimeMillis()

    // ---------------------------------------------------------------- preview

    /**
     * A small preview palette derived directly from the slider's HSV, not from
     * an already-built RGB colour. Two bugs lived in the previous version:
     * every role but Primary hard-coded its saturation and value, so moving
     * those sliders visibly changed nothing except a floored Primary; and
     * building the seed as RGB first and decomposing it back to HSV here lost
     * the hue entirely at 0% saturation or 0% brightness (hue is mathematically
     * undefined for pure greys and black), producing a fixed, slider-independent
     * set of colours. Every role now scales off the real saturation/value across
     * their full 0-100% range, and hue is never round-tripped through RGB.
     */
    /**
     * The palette to display: hand-set colours where the user provided them,
     * derived ones everywhere else. Keeping the derived values underneath means
     * switching manual mode off restores a coherent scheme instead of leaving
     * holes.
     */
    fun resolveRoles(
        hue: Float, saturation: Float, brightness: Float, dark: Boolean, amoled: Boolean,
        manual: Boolean, overrides: Map<String, Int>
    ): List<Pair<String, Int>> {
        val derived = previewRoles(hue, saturation, brightness, dark, amoled)
        if (!manual) return derived
        return derived.map { (role, color) -> role to (overrides[role] ?: color) }
    }

    fun previewRoles(
        hue: Float, saturation: Float, brightness: Float, dark: Boolean, amoled: Boolean
    ): List<Pair<String, Int>> {
        fun tone(hueShift: Float, sat: Float, value: Float): Int =
            Color.HSVToColor(
                floatArrayOf(
                    (hue + hueShift + 360f) % 360f,
                    sat.coerceIn(0f, 1f),
                    value.coerceIn(0f, 1f)
                )
            )
        val bg = when {
            amoled -> Color.BLACK
            dark -> tone(0f, saturation * 0.12f, 0.08f + brightness * 0.06f)
            else -> tone(0f, saturation * 0.06f, 0.99f - brightness * 0.05f)
        }
        val surface = if (dark) tone(0f, saturation * 0.16f, 0.14f + brightness * 0.08f)
        else tone(0f, saturation * 0.08f, 0.96f - brightness * 0.08f)
        return listOf(
            // Primary is exactly the colour being chosen - no floor, no override.
            // Follows how Material derives a scheme: secondary keeps the hue
            // at much lower chroma, tertiary sits a fixed step around the wheel.
            // Still an approximation of the real generator, which works in HCT.
            "Primary" to tone(0f, saturation, brightness),
            "Secondary" to tone(0f, saturation * 0.33f, brightness * 0.85f + 0.10f),
            "Tertiary" to tone(60f, saturation * 0.55f, brightness * 0.85f + 0.08f),
            "Surface" to surface,
            // Bars: app bar, footer, menus, dialogs and settings screens. These
            // sit between surface and background in the real scheme, which is
            // why they looked like a third colour nobody could set.
            "Bars" to if (dark) tone(0f, saturation * 0.20f, 0.18f + brightness * 0.10f)
            else tone(0f, saturation * 0.10f, 0.94f - brightness * 0.06f),
            "Background" to bg,
        )
    }
}
