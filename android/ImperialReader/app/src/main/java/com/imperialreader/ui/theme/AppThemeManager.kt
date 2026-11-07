package com.imperialreader.ui.theme

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.MaterialColors
import com.imperialreader.R

enum class AppThemeMode(val label: String, val delegateMode: Int) {
    SYSTEM("System", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("Light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("Dark", AppCompatDelegate.MODE_NIGHT_YES)
}

enum class AppColorScheme(
    val label: String,
    @StyleRes val style: Int
) {
    IMPERIAL("Imperial", R.style.Theme_Imperial),
    OCEAN("Ocean", R.style.Theme_Ocean),
    FOREST("Forest", R.style.Theme_Forest),
    ROSE("Rose", R.style.Theme_Rose),
    DYNAMIC("Device colors", R.style.Theme_Imperial)
}

data class AppThemePreviewColors(val primary: Int, val surface: Int, val text: Int)

object AppThemeManager {
    private const val PREFS = "appearance_settings"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_SCHEME = "color_scheme"

    fun initialize(context: Context) {
        AppCompatDelegate.setDefaultNightMode(currentMode(context).delegateMode)
    }

    fun currentMode(context: Context): AppThemeMode =
        AppThemeMode.entries.firstOrNull {
            it.name == preferences(context).getString(KEY_MODE, AppThemeMode.SYSTEM.name)
        } ?: AppThemeMode.SYSTEM

    fun currentScheme(context: Context): AppColorScheme =
        AppColorScheme.entries.firstOrNull {
            it.name == preferences(context).getString(KEY_SCHEME, AppColorScheme.IMPERIAL.name)
        } ?: AppColorScheme.IMPERIAL

    fun setMode(context: Context, mode: AppThemeMode) {
        preferences(context).edit().putString(KEY_MODE, mode.name).apply()
        AppCompatDelegate.setDefaultNightMode(mode.delegateMode)
    }

    fun setScheme(context: Context, scheme: AppColorScheme) {
        preferences(context).edit().putString(KEY_SCHEME, scheme.name).apply()
    }

    fun previewColors(context: Context, scheme: AppColorScheme): AppThemePreviewColors {
        val themedContext = ContextThemeWrapper(context, scheme.style)
        return AppThemePreviewColors(
            primary = if (scheme == AppColorScheme.DYNAMIC) 0xFF6750A4.toInt()
            else color(themedContext, com.google.android.material.R.attr.colorPrimary),
            surface = color(themedContext, com.google.android.material.R.attr.colorSurface),
            text = color(themedContext, com.google.android.material.R.attr.colorOnSurface)
        )
    }

    fun color(context: Context, attribute: Int): Int =
        MaterialColors.getColor(context, attribute, android.graphics.Color.BLACK)

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
