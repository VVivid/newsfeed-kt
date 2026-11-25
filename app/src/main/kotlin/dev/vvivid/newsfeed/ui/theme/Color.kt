package dev.vvivid.newsfeed.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Newsprint color schemes. Roles marked "design" use the agreed mockup tokens; the rest are
// derived from the same palette so that no Material 3 role falls back to the baseline purple.
//
// Two conventions the screens rely on:
// - Headings use `tertiary` (oxblood in light, blush in dark). Material 3 has no heading role,
//   and tertiary keeps headings coherent when dynamic color replaces these schemes.
// - Cards and input fields use `surfaceContainerLowest`, the design's card color. In the dark
//   scheme it is lighter than the background, as the design's dark cards are.

// "Fixed" roles keep the same color in light and dark.
private val PrimaryFixed = Color(0xFFF6D9D1)
private val PrimaryFixedDim = Color(0xFFFFB4A6)
private val OnPrimaryFixed = Color(0xFF49120A)
private val OnPrimaryFixedVariant = Color(0xFF73291E)
private val SecondaryFixed = Color(0xFFE4E2D4)
private val SecondaryFixedDim = Color(0xFFC9C8AA)
private val OnSecondaryFixed = Color(0xFF1F1F12)
private val OnSecondaryFixedVariant = Color(0xFF4A4838)
private val TertiaryFixed = Color(0xFFF6DDD3)
private val TertiaryFixedDim = Color(0xFFE2C7BE)
private val OnTertiaryFixed = Color(0xFF2E1218)
private val OnTertiaryFixedVariant = Color(0xFF5A3039)

internal val NewsprintLightColorScheme =
    lightColorScheme(
        primary = Color(0xFF903F32), // design
        onPrimary = Color(0xFFFFFFFF), // design
        primaryContainer = Color(0xFFF6D9D1), // design
        onPrimaryContainer = Color(0xFF49120A), // design
        inversePrimary = Color(0xFFFFB4A6),
        secondary = Color(0xFF6E6E50),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE4E2D4), // design
        onSecondaryContainer = Color(0xFF4A4838), // design
        tertiary = Color(0xFF49232D), // design heading color
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE2C7BE),
        onTertiaryContainer = Color(0xFF49232D),
        background = Color(0xFFF2EDE3), // design
        onBackground = Color(0xFF302824), // design
        surface = Color(0xFFF2EDE3), // design
        onSurface = Color(0xFF302824), // design
        surfaceVariant = Color(0xFFE8E2D3),
        onSurfaceVariant = Color(0xFF79594F), // design muted text
        inverseSurface = Color(0xFF302824),
        inverseOnSurface = Color(0xFFF2EDE3),
        error = Color(0xFFA1291B), // design
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD4),
        onErrorContainer = Color(0xFF410001),
        outline = Color(0xFF857467), // design
        outlineVariant = Color(0xFFDDD3C2), // design line
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFF2EDE3),
        surfaceContainerLowest = Color(0xFFFBF8F2), // design card
        surfaceContainerLow = Color(0xFFEEE8DD),
        surfaceContainer = Color(0xFFE8E2D3), // design
        surfaceContainerHigh = Color(0xFFE2DBCB),
        surfaceContainerHighest = Color(0xFFDCD4C3),
        surfaceDim = Color(0xFFD8CFBD),
        primaryFixed = PrimaryFixed,
        primaryFixedDim = PrimaryFixedDim,
        onPrimaryFixed = OnPrimaryFixed,
        onPrimaryFixedVariant = OnPrimaryFixedVariant,
        secondaryFixed = SecondaryFixed,
        secondaryFixedDim = SecondaryFixedDim,
        onSecondaryFixed = OnSecondaryFixed,
        onSecondaryFixedVariant = OnSecondaryFixedVariant,
        tertiaryFixed = TertiaryFixed,
        tertiaryFixedDim = TertiaryFixedDim,
        onTertiaryFixed = OnTertiaryFixed,
        onTertiaryFixedVariant = OnTertiaryFixedVariant,
    )

internal val NewsprintDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFFFB4A6), // design
        onPrimary = Color(0xFF561E15), // design
        primaryContainer = Color(0xFF73291E), // design
        onPrimaryContainer = Color(0xFFFFDAD3), // design
        inversePrimary = Color(0xFF903F32),
        secondary = Color(0xFFC9C8AA),
        onSecondary = Color(0xFF31311F),
        secondaryContainer = Color(0xFF3A3A2E), // design
        onSecondaryContainer = Color(0xFFE3E1CC), // design
        tertiary = Color(0xFFF6DDD3), // design heading color
        onTertiary = Color(0xFF49232D),
        tertiaryContainer = Color(0xFF5A3039),
        onTertiaryContainer = Color(0xFFF6DDD3),
        background = Color(0xFF1B1613), // design
        onBackground = Color(0xFFEDE0D8), // design
        surface = Color(0xFF1B1613), // design
        onSurface = Color(0xFFEDE0D8), // design
        surfaceVariant = Color(0xFF2A2320),
        onSurfaceVariant = Color(0xFFC7B1A8), // design muted text
        inverseSurface = Color(0xFFEDE0D8),
        inverseOnSurface = Color(0xFF302824),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFFA08D85), // design
        outlineVariant = Color(0xFF3A312B), // design line
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF3B332E),
        surfaceContainerLowest = Color(0xFF25201C), // design card
        surfaceContainerLow = Color(0xFF27211E),
        surfaceContainer = Color(0xFF2A2320), // design
        surfaceContainerHigh = Color(0xFF342C28),
        surfaceContainerHighest = Color(0xFF3F3632),
        surfaceDim = Color(0xFF1B1613),
        primaryFixed = PrimaryFixed,
        primaryFixedDim = PrimaryFixedDim,
        onPrimaryFixed = OnPrimaryFixed,
        onPrimaryFixedVariant = OnPrimaryFixedVariant,
        secondaryFixed = SecondaryFixed,
        secondaryFixedDim = SecondaryFixedDim,
        onSecondaryFixed = OnSecondaryFixed,
        onSecondaryFixedVariant = OnSecondaryFixedVariant,
        tertiaryFixed = TertiaryFixed,
        tertiaryFixedDim = TertiaryFixedDim,
        onTertiaryFixed = OnTertiaryFixed,
        onTertiaryFixedVariant = OnTertiaryFixedVariant,
    )
