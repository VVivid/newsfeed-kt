package dev.vvivid.newsfeed.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colors of the generated fallback covers, named as in the design's cover component. */
@Immutable
data class CoverPalette(
    val sand: Color,
    val sage: Color,
    val rose: Color,
    val cream: Color,
    val clay: Color,
    val wine: Color,
    val olive: Color,
    val mustard: Color,
    val brown: Color,
)

internal val LightCoverPalette =
    CoverPalette(
        sand = Color(0xFFD9CBB4),
        sage = Color(0xFFC9CFBF),
        rose = Color(0xFFE2C7BE),
        cream = Color(0xFFEFE6D6),
        clay = Color(0xFF903F32),
        wine = Color(0xFF49232D),
        olive = Color(0xFF6E6E50),
        mustard = Color(0xFFB9883A),
        brown = Color(0xFF866257),
    )

internal val DarkCoverPalette =
    CoverPalette(
        sand = Color(0xFF3B322A),
        sage = Color(0xFF343A30),
        rose = Color(0xFF43302B),
        cream = Color(0xFF2C2521),
        clay = Color(0xFFD9826F),
        wine = Color(0xFFF0C9BC),
        olive = Color(0xFFA9AC86),
        mustard = Color(0xFFD6AE68),
        brown = Color(0xFFB49A8F),
    )

/**
 * The cover palette of the enclosing [NewsfeedTheme]. Covers keep the newsprint palette even when
 * dynamic color is on; only the light or dark choice follows the theme.
 */
val LocalCoverPalette = staticCompositionLocalOf { LightCoverPalette }
