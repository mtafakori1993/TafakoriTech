package ir.tafakoritech.adminapplication.ui.theme

import android.content.Context

/** User-selectable app theme. [System] follows the device dark-mode setting. */
enum class ThemeMode { System, Light, Dark }

/** Persists the chosen [ThemeMode] in SharedPreferences. */
object ThemePreferences {
    private const val PREFS = "tt_settings"
    private const val KEY_THEME = "theme_mode"

    fun load(context: Context): ThemeMode {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, ThemeMode.System.name)
        return runCatching { ThemeMode.valueOf(name!!) }.getOrDefault(ThemeMode.System)
    }

    fun save(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode.name)
            .apply()
    }
}
