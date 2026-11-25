package dev.vvivid.newsfeed.ui.theme

/** The light or dark choice in Settings. */
enum class ThemeMode {
    /** Follow the system's dark theme setting. */
    System,
    Light,
    Dark,
    ;

    /** Whether the app uses its dark theme, given whether the system is in dark mode now. */
    fun isDark(systemInDarkTheme: Boolean): Boolean =
        when (this) {
            System -> systemInDarkTheme
            Light -> false
            Dark -> true
        }
}
