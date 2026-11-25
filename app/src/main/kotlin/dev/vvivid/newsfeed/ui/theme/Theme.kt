package dev.vvivid.newsfeed.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * The app's Material 3 theme: newsprint colors, Source Serif 4 headings and the matching
 * fallback-cover palette.
 *
 * @param dynamicColor use the wallpaper-based Material You colors instead of the newsprint
 *   schemes. Only honored on Android 12 (API 31) and newer.
 */
@Composable
fun NewsfeedTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                NewsprintDarkColorScheme
            }

            else -> {
                NewsprintLightColorScheme
            }
        }
    val coverPalette = if (darkTheme) DarkCoverPalette else LightCoverPalette

    CompositionLocalProvider(LocalCoverPalette provides coverPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NewsfeedTypography,
            content = content,
        )
    }
}
