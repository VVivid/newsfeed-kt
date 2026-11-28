package dev.vvivid.newsfeed.ui.theme

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The appearance choices in Settings. */
data class Appearance(
    val themeMode: ThemeMode = ThemeMode.System,
    val useDynamicColor: Boolean = false,
)

/**
 * Keeps [Appearance] in the app's preferences, so the theme stays as chosen across launches. It
 * is read once, when the app starts, so the first screen already has the chosen colors.
 */
class AppearanceStore(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val appearance: StateFlow<Appearance> = state.asStateFlow()

    fun update(appearance: Appearance) {
        state.value = appearance
        preferences.edit {
            putString(KEY_THEME_MODE, appearance.themeMode.name)
            putBoolean(KEY_DYNAMIC_COLOR, appearance.useDynamicColor)
        }
    }

    private fun load(): Appearance =
        Appearance(
            // An unknown name, from a later version, falls back to following the system.
            themeMode = ThemeMode.entries.firstOrNull { it.name == preferences.getString(KEY_THEME_MODE, null) } ?: ThemeMode.System,
            useDynamicColor = preferences.getBoolean(KEY_DYNAMIC_COLOR, false),
        )

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
    }
}
