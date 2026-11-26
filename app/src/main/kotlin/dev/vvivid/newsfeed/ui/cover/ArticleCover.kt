package dev.vvivid.newsfeed.ui.cover

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.vvivid.newsfeed.data.Article
import dev.vvivid.newsfeed.data.ThumbnailLoader

/**
 * The signed-in session's thumbnail loader. Without one, as in previews and most tests, every
 * article shows its fallback cover.
 */
val LocalThumbnailLoader = staticCompositionLocalOf<ThumbnailLoader?> { null }

/**
 * The article's thumbnail from the server, cropped to fill [modifier]'s size, or its generated
 * [FallbackCover] while the thumbnail loads, when there is none, or when it cannot be loaded. Like
 * the fallback cover it is decorative and hidden from accessibility services: the title says what
 * the article is.
 */
@Composable
fun ArticleCover(
    article: Article,
    modifier: Modifier = Modifier,
) {
    val loader = LocalThumbnailLoader.current
    // A thumbnail already in memory shows in the first frame; otherwise it loads after composition.
    var thumbnail by remember(loader, article.id, article.thumbnailKey) { mutableStateOf(loader?.cached(article)) }
    LaunchedEffect(loader, article.id, article.thumbnailKey) {
        if (thumbnail == null && loader != null) thumbnail = loader.load(article)
    }
    val bitmap = thumbnail
    if (bitmap == null) {
        FallbackCover(articleUrl = article.url, modifier = modifier)
    } else {
        Image(
            bitmap = remember(bitmap) { bitmap.asImageBitmap() },
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}
