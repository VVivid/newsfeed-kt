package dev.vvivid.newsfeed.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.vvivid.newsfeed.R

// Display, headline and title styles use Source Serif 4, as in the design. Body and label styles
// keep Material's system sans-serif. Sizes and line heights follow the Material 3 type scale.

private val MaterialBaseline = Typography()

private val SourceSerifWeights =
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

internal val NewsfeedTypography =
    Typography(
        displayLarge = MaterialBaseline.displayLarge.inSourceSerif(FontWeight.Bold),
        displayMedium = MaterialBaseline.displayMedium.inSourceSerif(FontWeight.Bold),
        displaySmall = MaterialBaseline.displaySmall.inSourceSerif(FontWeight.Bold),
        headlineLarge = MaterialBaseline.headlineLarge.inSourceSerif(FontWeight.Bold),
        headlineMedium = MaterialBaseline.headlineMedium.inSourceSerif(FontWeight.Bold),
        headlineSmall = MaterialBaseline.headlineSmall.inSourceSerif(FontWeight.Bold),
        titleLarge = MaterialBaseline.titleLarge.inSourceSerif(FontWeight.SemiBold),
        titleMedium = MaterialBaseline.titleMedium.inSourceSerif(FontWeight.SemiBold),
        titleSmall = MaterialBaseline.titleSmall.inSourceSerif(FontWeight.SemiBold),
    )

/** Article body text: Source Serif 4 at a size for long reading, as in the design's article screen. */
internal val ArticleBodyTextStyle =
    TextStyle(
        fontFamily = sourceSerif4(opticalSize = 18.sp),
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 29.sp,
        letterSpacing = 0.sp,
    )

/**
 * The summary above an article's body. The bundled font has no italic styles, so Android slants
 * the upright glyphs.
 */
internal val ArticleSummaryTextStyle = ArticleBodyTextStyle.copy(fontStyle = FontStyle.Italic, lineHeight = 27.sp)

private fun TextStyle.inSourceSerif(weight: FontWeight): TextStyle =
    copy(
        fontFamily = sourceSerif4(opticalSize = fontSize),
        fontWeight = weight,
        // The design sets its serif text without extra tracking.
        letterSpacing = 0.sp,
    )

/**
 * Source Serif 4 is one variable font file with weight and optical-size axes. Browsers match the
 * optical size to the text size automatically; Android needs it set, so each text style gets a
 * family at its own size. The optical size also follows the user's font scale.
 *
 * The opt-in is needed because Compose still marks variation settings on resource fonts as
 * experimental.
 */
@OptIn(ExperimentalTextApi::class)
private fun sourceSerif4(opticalSize: TextUnit): FontFamily =
    FontFamily(
        SourceSerifWeights.map { weight ->
            Font(
                resId = R.font.source_serif_4,
                weight = weight,
                variationSettings =
                    FontVariation.Settings(
                        FontVariation.weight(weight.weight),
                        FontVariation.opticalSizing(opticalSize),
                    ),
            )
        },
    )
