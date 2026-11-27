package dev.vvivid.newsfeed.reader

import android.content.Intent
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.vvivid.newsfeed.R
import dev.vvivid.newsfeed.data.Article
import dev.vvivid.newsfeed.data.Shelf
import dev.vvivid.newsfeed.ui.component.LoadFailedMessage
import dev.vvivid.newsfeed.ui.component.LoadingIndicator
import dev.vvivid.newsfeed.ui.cover.ArticleCover
import dev.vvivid.newsfeed.ui.preview.PreviewSampleData
import dev.vvivid.newsfeed.ui.preview.PreviewSamples
import dev.vvivid.newsfeed.ui.theme.ArticleSummaryTextStyle
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme

/**
 * The article reader. [onOpenOriginal] and [onOpenLink] receive the address to open; this screen
 * never opens one itself.
 */
@Composable
fun ArticleRoute(
    viewModel: ArticleViewModel,
    onBack: () -> Unit,
    onOpenOriginal: (url: String) -> Unit,
    onOpenLink: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ArticleScreen(
        uiState = uiState,
        onBack = onBack,
        onRetry = viewModel::retry,
        onAction = viewModel::perform,
        onShare = { article -> context.startActivity(shareIntent(article)) },
        onOpenOriginal = onOpenOriginal,
        onOpenLink = onOpenLink,
        onMessageShown = viewModel::messageShown,
        modifier = modifier,
    )
}

/** The system share sheet with the article's original address. */
private fun shareIntent(article: Article): Intent {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            // An article the agent wrote has no address to share, so its title stands in.
            .putExtra(Intent.EXTRA_TEXT, article.url ?: article.title)
            .putExtra(Intent.EXTRA_TITLE, article.title)
    return Intent.createChooser(send, null)
}

/** Renders [uiState] and reports the user's actions. */
@Composable
fun ArticleScreen(
    uiState: ArticleUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAction: (ArticleAction) -> Unit,
    onShare: (Article) -> Unit,
    onOpenOriginal: (url: String) -> Unit,
    onOpenLink: (url: String) -> Unit,
    onMessageShown: (ArticleMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val loaded = uiState as? ArticleUiState.Loaded
    if (loaded?.message != null) {
        MessageSnackbar(loaded.message, loaded.article.shelf, snackbarHostState, onMessageShown)
    }
    Scaffold(
        modifier = modifier,
        topBar = { ArticleTopBar(onBack) },
        bottomBar = {
            if (loaded != null) ArticleBottomBar(loaded, onAction, onShare, onOpenOriginal)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (uiState) {
            ArticleUiState.Loading -> {
                LoadingIndicator(
                    description = stringResource(R.string.article_loading),
                    modifier = Modifier.padding(innerPadding),
                )
            }

            ArticleUiState.LoadFailed -> {
                LoadFailedMessage(
                    message = stringResource(R.string.article_load_failed),
                    onRetry = onRetry,
                    modifier = Modifier.padding(innerPadding),
                )
            }

            is ArticleUiState.Loaded -> {
                ArticleContent(uiState.article, uiState.body, innerPadding, onOpenOriginal, onOpenLink)
            }
        }
    }
}

/** Shows [message] in a snackbar; [shelf] is where the article is now. */
@Composable
private fun MessageSnackbar(
    message: ArticleMessage,
    shelf: Shelf,
    snackbarHostState: SnackbarHostState,
    onMessageShown: (ArticleMessage) -> Unit,
) {
    val text = stringResource(messageText(message, shelf))
    val currentOnMessageShown by rememberUpdatedState(onMessageShown)
    LaunchedEffect(message) {
        snackbarHostState.showSnackbar(text)
        currentOnMessageShown(message)
    }
}

@StringRes
private fun messageText(
    message: ArticleMessage,
    shelf: Shelf,
): Int =
    when (message) {
        is ArticleMessage.Done -> {
            when (message.action) {
                ArticleAction.Archive -> {
                    R.string.message_archived
                }

                ArticleAction.Unarchive -> {
                    R.string.message_unarchived
                }

                ArticleAction.MoveToTrash -> {
                    R.string.message_moved_to_trash
                }

                ArticleAction.Restore -> {
                    if (shelf == Shelf.Archive) R.string.message_restored_to_archive else R.string.message_restored_to_active
                }
            }
        }

        is ArticleMessage.Failed -> {
            when (message.action) {
                ArticleAction.Archive -> R.string.message_archive_failed
                ArticleAction.Unarchive -> R.string.message_unarchive_failed
                ArticleAction.MoveToTrash -> R.string.message_move_to_trash_failed
                ArticleAction.Restore -> R.string.message_restore_failed
            }
        }

        ArticleMessage.ChangedOnServer -> {
            R.string.message_changed_on_server_article
        }

        ArticleMessage.ChangeNotAllowed -> {
            R.string.message_change_not_allowed
        }
    }

// TopAppBar is still experimental in Material 3.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.back_to_news),
                )
            }
        },
    )
}

/** The shelf changes for the article, Share, and Open original. */
@Composable
private fun ArticleBottomBar(
    state: ArticleUiState.Loaded,
    onAction: (ArticleAction) -> Unit,
    onShare: (Article) -> Unit,
    onOpenOriginal: (url: String) -> Unit,
) {
    val article = state.article
    BottomAppBar(
        actions = {
            for (action in articleActions(article.shelf)) {
                ArticleActionButton(action, enabled = !state.isUpdating, onClick = { onAction(action) })
            }
            IconButton(onClick = { onShare(article) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_share),
                    contentDescription = stringResource(R.string.action_share),
                )
            }
        },
        floatingActionButton = {
            // An article the agent wrote without a source has no original to open.
            val url = article.url
            if (url != null) {
                val label = stringResource(R.string.action_open_original)
                ExtendedFloatingActionButton(
                    text = { Text(label) },
                    // This FAB hides its text from accessibility services, so the icon carries the label.
                    icon = { Icon(painter = painterResource(R.drawable.ic_open_in_new), contentDescription = label) },
                    onClick = { onOpenOriginal(url) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                )
            }
        },
    )
}

@Composable
private fun ArticleActionButton(
    action: ArticleAction,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(action.labelRes)
    if (action == ArticleAction.Restore) {
        // The Trash note tells people to tap Restore, so the button shows its name.
        TextButton(onClick = onClick, enabled = enabled) {
            Icon(
                painter = painterResource(action.iconRes),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Text(text = label, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
    } else {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(painter = painterResource(action.iconRes), contentDescription = label)
        }
    }
}

@get:StringRes
private val ArticleAction.labelRes: Int
    get() =
        when (this) {
            ArticleAction.Archive -> R.string.action_archive
            ArticleAction.Unarchive -> R.string.action_unarchive
            ArticleAction.MoveToTrash -> R.string.action_move_to_trash
            ArticleAction.Restore -> R.string.action_restore
        }

@get:DrawableRes
private val ArticleAction.iconRes: Int
    get() =
        when (this) {
            ArticleAction.Archive -> R.drawable.ic_archive
            ArticleAction.Unarchive -> R.drawable.ic_unarchive
            ArticleAction.MoveToTrash -> R.drawable.ic_delete
            ArticleAction.Restore -> R.drawable.ic_restore
        }

@Composable
private fun ArticleContent(
    article: Article,
    body: List<BodyBlock>,
    innerPadding: PaddingValues,
    onOpenOriginal: (url: String) -> Unit,
    onOpenLink: (url: String) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection),
                top = innerPadding.calculateTopPadding(),
                end = innerPadding.calculateEndPadding(layoutDirection),
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ArticleCover(
                article = article,
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(20.dp)),
            )
        }
        item { ArticleHeader(article, modifier = Modifier.padding(start = 24.dp, top = 6.dp, end = 24.dp)) }
        val url = article.url
        if (body.isEmpty() && url != null) {
            item {
                NoSavedBody(onOpenOriginal = { onOpenOriginal(url) }, modifier = Modifier.padding(horizontal = 24.dp))
            }
        } else {
            items(body) { block ->
                BodyBlockView(block, onLinkClick = onOpenLink, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
    }
}

@Composable
private fun ArticleHeader(
    article: Article,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val savedDate =
        remember(article.createdAt) {
            DateUtils.formatDateTime(
                context,
                article.createdAt.toEpochMilli(),
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
            )
        }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(
                    text = article.sourceHost ?: stringResource(R.string.news_source_agent),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = stringResource(R.string.article_saved_on, savedDate),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = article.title,
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(text = article.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = ArticleSummaryTextStyle)
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Shown instead of the body when the agent saved only the summary. */
@Composable
private fun NoSavedBody(
    onOpenOriginal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = stringResource(R.string.article_no_saved_body), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onOpenOriginal) {
            Icon(
                painter = painterResource(R.drawable.ic_open_in_new),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Text(
                text = stringResource(R.string.action_open_original),
                modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
            )
        }
    }
}

@Composable
private fun ArticleScreenPreview(uiState: ArticleUiState) {
    NewsfeedTheme {
        ArticleScreen(
            uiState = uiState,
            onBack = {},
            onRetry = {},
            onAction = {},
            onShare = {},
            onOpenOriginal = {},
            onOpenLink = {},
            onMessageShown = {},
        )
    }
}

@OptIn(PreviewSampleData::class)
@PreviewLightDark
@Composable
private fun ArticleActivePreview() {
    ArticleScreenPreview(ArticleUiState.Loaded(PreviewSamples.activeArticles.first(), PreviewSamples.articleBody))
}

@OptIn(PreviewSampleData::class)
@PreviewLightDark
@Composable
private fun ArticleArchivedPreview() {
    ArticleScreenPreview(ArticleUiState.Loaded(PreviewSamples.archivedArticles.first(), PreviewSamples.articleBody))
}

@OptIn(PreviewSampleData::class)
@PreviewLightDark
@Composable
private fun ArticleInTrashPreview() {
    ArticleScreenPreview(ArticleUiState.Loaded(PreviewSamples.trashedArticles.first(), PreviewSamples.articleBody))
}

@OptIn(PreviewSampleData::class)
@PreviewLightDark
@Composable
private fun ArticleWithoutBodyPreview() {
    ArticleScreenPreview(ArticleUiState.Loaded(PreviewSamples.activeArticles[1], body = emptyList()))
}

@PreviewLightDark
@Composable
private fun ArticleLoadingPreview() {
    ArticleScreenPreview(ArticleUiState.Loading)
}

@PreviewLightDark
@Composable
private fun ArticleLoadFailedPreview() {
    ArticleScreenPreview(ArticleUiState.LoadFailed)
}
