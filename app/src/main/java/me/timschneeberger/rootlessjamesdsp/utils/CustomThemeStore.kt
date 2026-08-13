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

    data class CustomTheme(
        val id: String,
        var name: String,
        var seed: Int,
        var dark: Boolean = true,
        var amoled: Boolean = false,
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
                out.add(
                    CustomTheme(
                        o.getString("id"),
                        o.getString("name"),
                        o.getInt("seed"),
                        o.optBoolean("dark", true),
                        o.optBoolean("amoled", false),
                    )
                )
            }
        }
        return out
    }

    fun save(ctx: Context, themes: List<CustomTheme>) {
        val arr = JSONArray()
        themes.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("seed", it.seed)
                    .put("dark", it.dark)
                    .put("amoled", it.amoled)
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
     * A small preview palette derived from the seed. This mirrors the shape of
     * what the theme engine produces so the preview is honest, without trying
     * to reproduce every tone exactly.
     */
    fun previewRoles(seed: Int, dark: Boolean, amoled: Boolean): List<Pair<String, Int>> {
        val hsv = FloatArray(3)
        Color.colorToHSV(seed, hsv)
        fun tone(hueShift: Float, sat: Float, value: Float): Int =
            Color.HSVToColor(
                floatArrayOf(
                    (hsv[0] + hueShift + 360f) % 360f,
                    sat.coerceIn(0f, 1f),
                    value.coerceIn(0f, 1f)
                )
            )
        val bg = when {
            amoled -> Color.BLACK
            dark -> tone(0f, 0.06f, 0.09f)
            else -> tone(0f, 0.03f, 0.99f)
        }
        val surface = if (dark) tone(0f, 0.08f, if (amoled) 0.10f else 0.16f)
        else tone(0f, 0.04f, 0.96f)
        return listOf(
            "Primary" to if (dark) tone(0f, hsv[1].coerceAtLeast(0.45f), 0.85f)
            else tone(0f, hsv[1].coerceAtLeast(0.55f), 0.60f),
            "Secondary" to tone(-28f, 0.35f, if (dark) 0.78f else 0.62f),
            "Tertiary" to tone(38f, 0.42f, if (dark) 0.80f else 0.60f),
            "Surface" to surface,
            "Background" to bg,
        )
    }
}
