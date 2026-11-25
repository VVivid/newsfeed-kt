package dev.vvivid.newsfeed.news

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.vvivid.newsfeed.R
import dev.vvivid.newsfeed.data.Article
import dev.vvivid.newsfeed.data.Shelf
import dev.vvivid.newsfeed.ui.component.LoadFailedMessage
import dev.vvivid.newsfeed.ui.component.LoadingIndicator
import dev.vvivid.newsfeed.ui.component.Notice
import dev.vvivid.newsfeed.ui.cover.FallbackCover
import dev.vvivid.newsfeed.ui.preview.PreviewSampleData
import dev.vvivid.newsfeed.ui.preview.PreviewSamples
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun NewsRoute(
    viewModel: NewsViewModel,
    onOpenArticle: (Article) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Articles may have moved while another screen was open, for example archived in the reader,
    // and new ones may have arrived while the app was in the background. If the view model's first
    // load is still running, refresh() does nothing.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    // Ages such as "2 hr. ago" count from when the shown articles were loaded.
    val now = remember(uiState.content) { Instant.now() }
    NewsScreen(
        uiState = uiState,
        now = now,
        onShelfSelect = viewModel::selectShelf,
        onRefresh = viewModel::refresh,
        onArticleClick = onOpenArticle,
        onArchive = viewModel::archive,
        onMoveToTrash = viewModel::moveToTrash,
        onUndo = viewModel::undo,
        onMessageShown = viewModel::messageShown,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

/**
 * The News list: the Active, Archive and Trash shelves. Renders [uiState] and reports the user's
 * actions; [now] is the moment article ages are counted from.
 */
@Composable
fun NewsScreen(
    uiState: NewsUiState,
    now: Instant,
    onShelfSelect: (Shelf) -> Unit,
    onRefresh: () -> Unit,
    onArticleClick: (Article) -> Unit,
    onArchive: (Article) -> Unit,
    onMoveToTrash: (Article) -> Unit,
    onUndo: (NewsMessage.Moved) -> Unit,
    onMessageShown: (NewsMessage) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageSnackbar(uiState.message, snackbarHostState, onUndo, onMessageShown)
    Scaffold(
        modifier = modifier,
        topBar = { NewsTopBar(onOpenSettings) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier =
                Modifier.padding(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    top = innerPadding.calculateTopPadding(),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                ),
        ) {
            ShelfSelector(
                selected = uiState.shelf,
                onSelect = onShelfSelect,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 12.dp),
            )
            // The list scrolls behind the navigation bar and ends above it.
            val listPadding =
                PaddingValues(start = 16.dp, end = 16.dp, bottom = innerPadding.calculateBottomPadding() + 28.dp)
            when (val content = uiState.content) {
                ShelfContent.Loading -> {
                    LoadingIndicator(description = stringResource(R.string.news_loading))
                }

                ShelfContent.LoadFailed -> {
                    LoadFailedMessage(message = stringResource(R.string.news_load_failed), onRetry = onRefresh)
                }

                is ShelfContent.Loaded -> {
                    ArticleList(
                        shelf = uiState.shelf,
                        content = content,
                        now = now,
                        contentPadding = listPadding,
                        onRefresh = onRefresh,
                        onArticleClick = onArticleClick,
                        onArchive = onArchive,
                        onMoveToTrash = onMoveToTrash,
                    )
                }
            }
        }
    }
}

/** Shows [message] in a snackbar, with Undo after a move, and reports how it closed. */
@Composable
private fun MessageSnackbar(
    message: NewsMessage?,
    snackbarHostState: SnackbarHostState,
    onUndo: (NewsMessage.Moved) -> Unit,
    onMessageShown: (NewsMessage) -> Unit,
) {
    if (message == null) return
    val text = stringResource(message.textRes)
    val undoLabel = stringResource(R.string.action_undo)
    val currentOnUndo by rememberUpdatedState(onUndo)
    val currentOnMessageShown by rememberUpdatedState(onMessageShown)
    LaunchedEffect(message) {
        val canUndo = message is NewsMessage.Moved
        val result =
            snackbarHostState.showSnackbar(
                message = text,
                actionLabel = if (canUndo) undoLabel else null,
                duration = if (canUndo) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        if (result == SnackbarResult.ActionPerformed && message is NewsMessage.Moved) {
            currentOnUndo(message)
        } else {
            currentOnMessageShown(message)
        }
    }
}

@get:StringRes
private val NewsMessage.textRes: Int
    get() =
        when (this) {
            is NewsMessage.Moved -> {
                when (move) {
                    ArticleMove.Archive -> R.string.message_archived
                    ArticleMove.Trash -> R.string.message_moved_to_trash
                }
            }

            is NewsMessage.MoveFailed -> {
                when (move) {
                    ArticleMove.Archive -> R.string.message_archive_failed
                    ArticleMove.Trash -> R.string.message_move_to_trash_failed
                }
            }

            is NewsMessage.UndoFailed -> {
                when (move) {
                    ArticleMove.Archive -> R.string.message_undo_archive_failed
                    ArticleMove.Trash -> R.string.message_undo_move_to_trash_failed
                }
            }
        }

// TopAppBar is still experimental in Material 3.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewsTopBar(onOpenSettings: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.app_name),
                modifier = Modifier.semantics { heading() },
                // Headings use tertiary, the design's heading color (see ui/theme/Color.kt).
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.headlineMedium,
            )
        },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_account),
                    contentDescription = stringResource(R.string.news_open_settings),
                )
            }
        },
    )
}

@Composable
private fun ShelfSelector(
    selected: Shelf,
    onSelect: (Shelf) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors =
        SegmentedButtonDefaults.colors(
            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        Shelf.entries.forEachIndexed { index, shelf ->
            SegmentedButton(
                selected = shelf == selected,
                onClick = { onSelect(shelf) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = Shelf.entries.size),
                colors = colors,
                label = { Text(stringResource(shelf.labelRes)) },
            )
        }
    }
}

@get:StringRes
private val Shelf.labelRes: Int
    get() =
        when (this) {
            Shelf.Active -> R.string.shelf_active
            Shelf.Archive -> R.string.shelf_archive
            Shelf.Trash -> R.string.shelf_trash
        }

/** Keys of the list items that are not articles. Article items use their IDs, which are strings. */
private enum class ListItemKey { StaleNotice, TrashNote, Empty, LatestLabel, EarlierLabel, ShelfLabel, SwipeHint }

@Composable
private fun ArticleList(
    shelf: Shelf,
    content: ShelfContent.Loaded,
    now: Instant,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onArticleClick: (Article) -> Unit,
    onArchive: (Article) -> Unit,
    onMoveToTrash: (Article) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = content.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (content.isStale) {
                item(key = ListItemKey.StaleNotice) { StaleNotice(onRetry = onRefresh, modifier = Modifier.animateItem()) }
            }
            if (shelf == Shelf.Trash) {
                item(key = ListItemKey.TrashNote) {
                    Notice(
                        icon = R.drawable.ic_restore,
                        text = stringResource(R.string.news_trash_note),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            when {
                content.articles.isEmpty() -> {
                    item(key = ListItemKey.Empty) { EmptyShelf(shelf, modifier = Modifier.animateItem()) }
                }

                shelf == Shelf.Active -> {
                    activeArticles(content.articles, now, onArticleClick, onArchive, onMoveToTrash)
                }

                else -> {
                    val label = if (shelf == Shelf.Archive) R.string.news_section_archived else R.string.news_section_in_trash
                    item(key = ListItemKey.ShelfLabel) { SectionLabel(label, modifier = Modifier.animateItem()) }
                    items(content.articles, key = { it.id }) { article ->
                        CompactArticleCard(
                            article = article,
                            now = now,
                            onClick = { onArticleClick(article) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The Active shelf: the newest article as a lead card, the rest as compact cards, all swipeable.
 * [articles] must not be empty; its first article is the lead.
 */
private fun LazyListScope.activeArticles(
    articles: List<Article>,
    now: Instant,
    onArticleClick: (Article) -> Unit,
    onArchive: (Article) -> Unit,
    onMoveToTrash: (Article) -> Unit,
) {
    val lead = articles.first()
    item(key = ListItemKey.LatestLabel) { SectionLabel(R.string.news_section_latest, modifier = Modifier.animateItem()) }
    // A swiped card is already off-screen when its article leaves the list, so swipeable cards do not
    // fade out: a fade would show the card sliding back into place (see SwipeToMove).
    item(key = lead.id) {
        SwipeToMove(lead, onArchive, onMoveToTrash, modifier = Modifier.animateItem(fadeOutSpec = null)) { moveActions ->
            LeadArticleCard(lead, now, onClick = { onArticleClick(lead) }, modifier = moveActions)
        }
    }
    val earlier = articles.drop(1)
    if (earlier.isNotEmpty()) {
        item(key = ListItemKey.EarlierLabel) { SectionLabel(R.string.news_section_earlier, modifier = Modifier.animateItem()) }
        items(earlier, key = { it.id }) { article ->
            SwipeToMove(article, onArchive, onMoveToTrash, modifier = Modifier.animateItem(fadeOutSpec = null)) { moveActions ->
                CompactArticleCard(article, now, onClick = { onArticleClick(article) }, modifier = moveActions)
            }
        }
    }
    item(key = ListItemKey.SwipeHint) {
        Text(
            text = stringResource(R.string.news_swipe_hint),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).animateItem(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Lets the user swipe [article] left to archive it or right to move it to Trash. [card] gets a
 * modifier carrying the same two actions for accessibility services; it must go on the card's
 * clickable surface, the element TalkBack focuses.
 *
 * A swipe only reports the move; the list decides whether the card goes. The view model takes the
 * article out at once, but puts it back after Undo or when the move fails, sometimes before the
 * card has even left. A card that comes back must be in place and must not report the move again,
 * so:
 * - The swipe state is not saved. LazyColumn restores saved item state by key, so the returning
 *   article would get a swiped-away state, and Material reports a swiped-away state as a new swipe.
 * - Each swipe is reported once, then [SwipeToDismissBoxState.reset] slides the card back. If the
 *   article stays, the card is in place again; if it goes, the card leaves before it gets far.
 * - The list drops a swiped card without fading it out (see activeArticles), so the slide back
 *   does not show.
 */
@Composable
private fun SwipeToMove(
    article: Article,
    onArchive: (Article) -> Unit,
    onMoveToTrash: (Article) -> Unit,
    modifier: Modifier = Modifier,
    card: @Composable (moveActions: Modifier) -> Unit,
) {
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold) }
    val scope = rememberCoroutineScope()
    // Material calls onDismiss again if the callback changes while the box is still swiped away,
    // which lasts until reset() ends. This flag keeps such a call from reporting the swipe twice.
    var isSwipeReported by remember { mutableStateOf(false) }
    val archiveLabel = stringResource(R.string.action_archive)
    val moveToTrashLabel = stringResource(R.string.action_move_to_trash)
    SwipeToDismissBox(
        state = state,
        backgroundContent = { SwipeBackground(state.dismissDirection) },
        modifier = modifier,
        onDismiss = { direction ->
            if (!isSwipeReported) {
                isSwipeReported = true
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> onArchive(article)
                    SwipeToDismissBoxValue.StartToEnd -> onMoveToTrash(article)
                    SwipeToDismissBoxValue.Settled -> Unit
                }
                scope.launch {
                    try {
                        state.reset()
                    } finally {
                        isSwipeReported = false
                    }
                }
            }
        },
    ) {
        card(
            Modifier.semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(archiveLabel) {
                            onArchive(article)
                            true
                        },
                        CustomAccessibilityAction(moveToTrashLabel) {
                            onMoveToTrash(article)
                            true
                        },
                    )
            },
        )
    }
}

/** What a swipe will do, revealed behind the card as it moves. */
@Composable
private fun RowScope.SwipeBackground(direction: SwipeToDismissBoxValue) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val archives = direction == SwipeToDismissBoxValue.EndToStart
    val containerColor =
        if (archives) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val contentColor =
        if (archives) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(CardShape)
                .background(containerColor)
                .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (archives) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(if (archives) R.drawable.ic_archive else R.drawable.ic_delete),
            contentDescription = null,
            tint = contentColor,
        )
        Text(
            text = stringResource(if (archives) R.string.action_archive else R.string.action_move_to_trash),
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private val CardShape = RoundedCornerShape(16.dp)

@Composable
private fun ArticleCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        content()
    }
}

/** The newest Active article, with a wide cover. */
@Composable
private fun LeadArticleCard(
    article: Article,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ArticleCard(onClick = onClick, modifier = modifier) {
        Column {
            FallbackCover(articleUrl = article.url, modifier = Modifier.fillMaxWidth().height(176.dp))
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ArticleMeta(article, now)
                Text(
                    text = article.title,
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(text = article.summary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CompactArticleCard(
    article: Article,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ArticleCard(onClick = onClick, modifier = modifier) {
        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ArticleMeta(article, now)
                Text(
                    text = article.title,
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = article.summary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FallbackCover(
                articleUrl = article.url,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
            )
        }
    }
}

/** The source and how long ago the article arrived on its shelf: saved, archived or trashed. */
@Composable
private fun ArticleMeta(
    article: Article,
    now: Instant,
) {
    Text(
        text = stringResource(R.string.news_article_meta, article.sourceHost, relativeAge(article.shelvedAt, now)),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
    )
}

/** How long before [now] [time] was, such as "5 min. ago", "Yesterday" or "Nov 18", in the user's language. */
private fun relativeAge(
    time: Instant,
    now: Instant,
): String =
    DateUtils
        .getRelativeTimeSpanString(
            time.toEpochMilli(),
            now.toEpochMilli(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE or DateUtils.FORMAT_ABBREV_MONTH,
        ).toString()

@Composable
private fun SectionLabel(
    @StringRes text: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(text),
        modifier = modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** A refresh failed while older articles stay on screen. */
@Composable
private fun StaleNotice(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.news_stale),
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = onRetry,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun EmptyShelf(
    shelf: Shelf,
    modifier: Modifier = Modifier,
) {
    val (title, body) =
        when (shelf) {
            Shelf.Active -> R.string.news_empty_active_title to R.string.news_empty_active_body
            Shelf.Archive -> R.string.news_empty_archive_title to R.string.news_empty_archive_body
            Shelf.Trash -> R.string.news_empty_trash_title to R.string.news_empty_trash_body
        }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(title),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.tertiary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(PreviewSampleData::class)
@Composable
private fun NewsScreenPreview(
    shelf: Shelf = Shelf.Active,
    content: ShelfContent,
) {
    NewsfeedTheme {
        NewsScreen(
            uiState = NewsUiState(shelf = shelf, content = content),
            now = PreviewSamples.now,
            onShelfSelect = {},
            onRefresh = {},
            onArticleClick = {},
            onArchive = {},
            onMoveToTrash = {},
            onUndo = {},
            onMessageShown = {},
            onOpenSettings = {},
        )
    }
}

@OptIn(PreviewSampleData::class)
@PreviewLightDark
@Composable
private fun NewsActivePreview() {
    NewsScreenPreview(content = ShelfContent.Loaded(PreviewSamples.activeArticles))
}

@OptIn(PreviewSampleData::class)
@PreviewLightDark
@Composable
private fun NewsArchivePreview() {
    NewsScreenPreview(shelf = Shelf.Archive, content = ShelfContent.Loaded(PreviewSamples.archivedArticles))
}

@OptIn(PreviewSampleData::class)
@PreviewLightDark
@Composable
private fun NewsTrashPreview() {
    NewsScreenPreview(shelf = Shelf.Trash, content = ShelfContent.Loaded(PreviewSamples.trashedArticles))
}

@OptIn(PreviewSampleData::class)
@PreviewLightDark
@Composable
private fun NewsStalePreview() {
    NewsScreenPreview(content = ShelfContent.Loaded(PreviewSamples.activeArticles, isStale = true))
}

@PreviewLightDark
@Composable
private fun NewsEmptyPreview() {
    NewsScreenPreview(content = ShelfContent.Loaded(emptyList()))
}

@PreviewLightDark
@Composable
private fun NewsLoadingPreview() {
    NewsScreenPreview(content = ShelfContent.Loading)
}

@PreviewLightDark
@Composable
private fun NewsLoadFailedPreview() {
    NewsScreenPreview(content = ShelfContent.LoadFailed)
}
