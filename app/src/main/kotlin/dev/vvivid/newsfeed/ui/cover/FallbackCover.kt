package dev.vvivid.newsfeed.ui.cover

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import dev.vvivid.newsfeed.ui.theme.LocalCoverPalette
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme

/**
 * Generated cover for an article that has no thumbnail. The pattern is chosen by
 * [fallbackCoverIndex], so an article keeps its cover everywhere it appears.
 *
 * The cover is decorative and hidden from accessibility services. It is drawn as vector shapes
 * that scale with its size, so give it a size through [modifier] and clip it to the shape the
 * layout needs. Colors follow the light or dark [NewsfeedTheme].
 */
@Composable
fun FallbackCover(
    articleUrl: String,
    modifier: Modifier = Modifier,
) {
    FallbackCover(pattern = CoverPattern.entries[fallbackCoverIndex(articleUrl)], modifier = modifier)
}

/** Draws one specific fallback [pattern]; see the other overload for choosing it by article. */
@Composable
fun FallbackCover(
    pattern: CoverPattern,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCoverPalette.current
    Canvas(modifier.clipToBounds()) {
        drawCoverPattern(pattern, palette)
    }
}

@PreviewLightDark
@Composable
private fun FallbackCoverGalleryPreview() {
    NewsfeedTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoverPattern.entries.chunked(4).forEach { rowPatterns ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowPatterns.forEach { pattern ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FallbackCover(
                                    pattern = pattern,
                                    modifier = Modifier.size(width = 96.dp, height = 72.dp).clip(RoundedCornerShape(12.dp)),
                                )
                                Text(
                                    text = "${(pattern.ordinal + 1).toString().padStart(2, '0')} ${pattern.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
