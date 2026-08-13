package me.timschneeberger.rootlessjamesdsp.delegates

import android.app.Activity
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.model.preference.AppTheme
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

interface ThemingDelegate {
    fun applyAppTheme(activity: Activity)

    companion object {
        fun getThemeResIds(appTheme: AppTheme, isAmoled: Boolean): List<Int> {
            val resIds = mutableListOf<Int>()
            resIds += when (appTheme) {
                AppTheme.MONET -> R.style.Theme_RootlessJamesDSP_Monet
                AppTheme.VIPER -> R.style.Theme_RootlessJamesDSP_Viper
                AppTheme.V4A_CLASSIC -> R.style.Theme_RootlessJamesDSP_V4AClassic
                AppTheme.CUSTOM -> R.style.Theme_RootlessJamesDSP
                AppTheme.GREEN_APPLE -> R.style.Theme_RootlessJamesDSP_GreenApple
                AppTheme.STRAWBERRY_DAIQUIRI -> R.style.Theme_RootlessJamesDSP_StrawberryDaiquiri
                AppTheme.HONEY -> R.style.Theme_RootlessJamesDSP_Honey
                AppTheme.TEALTURQUOISE -> R.style.Theme_RootlessJamesDSP_TealTurquoise
                AppTheme.YINYANG -> R.style.Theme_RootlessJamesDSP_YinYang
                AppTheme.YOTSUBA -> R.style.Theme_RootlessJamesDSP_Yotsuba
                AppTheme.TIDAL_WAVE -> R.style.Theme_RootlessJamesDSP_TidalWave
                else -> R.style.Theme_RootlessJamesDSP
            }

            if (isAmoled) {
                resIds += R.style.ThemeOverlay_RootlessJamesDSP_Amoled
            }

            return resIds
        }
    }
}

class ThemingDelegateImpl : ThemingDelegate, KoinComponent {
    private val preferences: Preferences.App by inject()

    override fun applyAppTheme(activity: Activity) {
        val isAmoled = preferences.get<Boolean>(R.string.key_appearance_pure_black)
        // V4A mode brings the ViPER look with it; the stored theme choice is
        // untouched and returns when the mode is switched off.
        if (me.timschneeberger.rootlessjamesdsp.utils.V4aMode.isOn(activity)) {
            // Dedicated ViPER4Android classic look: green on dark, like the
            // original FX Material app. The stored theme choice is untouched.
            activity.setTheme(R.style.Theme_RootlessJamesDSP_V4AClassic)
            if (isAmoled) activity.theme.applyStyle(R.style.ThemeOverlay_RootlessJamesDSP_Amoled, true)
            return
        }
        val appTheme = AppTheme.valueOf(preferences.get((R.string.key_appearance_app_theme)))
        ThemingDelegate.getThemeResIds(appTheme, isAmoled).forEach { activity.setTheme(it) }

        // A custom theme is a seed colour: the palette is generated from it at
        // apply time, so the scheme stays coherent instead of being a pile of
        // hand-picked values that clash.
        if (appTheme == AppTheme.CUSTOM) {
            me.timschneeberger.rootlessjamesdsp.utils.CustomThemeStore.active(activity)?.let { t ->
                if (t.amoled)
                    activity.theme.applyStyle(R.style.ThemeOverlay_RootlessJamesDSP_Amoled, true)
                com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(
                    activity,
                    com.google.android.material.color.DynamicColorsOptions.Builder()
                        .setContentBasedSource(t.seed)
                        .build()
                )
            }
        }
    }
}
