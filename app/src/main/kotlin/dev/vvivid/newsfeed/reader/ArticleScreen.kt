package dev.vvivid.newsfeed.reader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
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
import kotlinx.coroutines.launch

/**
 * The article reader. [onOpenOriginal] and [onOpenLink] receive the address to open; this screen
 * never opens one itself.
 */
@Composable
fun ArticleRoute(
    viewModel: ArticleViewModel,
    audioViewModel: ArticleAudioViewModel,
    onBack: () -> Unit,
    onOpenOriginal: (url: String) -> Unit,
    onOpenLink: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val audioState by audioViewModel.uiState.collectAsStateWithLifecycle()
    val listenState by audioViewModel.listenState.collectAsStateWithLifecycle()
    val audioMessage by audioViewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val loaded = uiState as? ArticleUiState.Loaded
    if (loaded != null) {
        // The audio's parts are made from the body as the reader shows it, so their blocks match.
        LaunchedEffect(loaded.article, loaded.body, loaded.narration) {
            audioViewModel.bind(loaded.article, readerBody(loaded.body, loaded.article.title), loaded.narration)
        }
    }
    LifecycleStartEffect(audioViewModel) {
        audioViewModel.setVisible(true)
        onStopOrDispose { audioViewModel.setVisible(false) }
    }
    val audioActions =
        remember(audioViewModel) {
            AudioActions(
                onCreate = audioViewModel::create,
                onPlaySample = audioViewModel::playSample,
                onCancel = audioViewModel::cancel,
                onRetry = audioViewModel::retry,
                onDelete = audioViewModel::delete,
                onListen = audioViewModel::listen,
                onPause = audioViewModel::pause,
                onSkip = audioViewModel::skip,
                onStepPart = audioViewModel::stepPart,
                onPreviousSection = audioViewModel::previousSection,
                onNextSection = audioViewModel::nextSection,
                onSeekToFraction = audioViewModel::seekToFraction,
                onSpeed = audioViewModel::setSpeed,
                onSleepTimer = audioViewModel::setSleepTimer,
                onMessageShown = audioViewModel::messageShown,
            )
        }
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
        audioState = audioState,
        listenState = listenState,
        audioMessage = audioMessage,
        audioActions = audioActions,
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
    audioState: AudioUiState = AudioUiState.Unavailable,
    listenState: ListenState = ListenState(),
    audioMessage: AudioMessage? = null,
    audioActions: AudioActions = AudioActions(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val loaded = uiState as? ArticleUiState.Loaded
    if (loaded?.message != null) {
        MessageSnackbar(loaded.message, loaded.article.shelf, snackbarHostState, onMessageShown)
    }
    if (audioMessage != null) AudioMessageSnackbar(audioMessage, snackbarHostState, audioActions.onMessageShown)
    var showCreateAudio by rememberSaveable { mutableStateOf(false) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteAudio by rememberSaveable { mutableStateOf(false) }
    var confirmTrash by rememberSaveable { mutableStateOf(false) }
    val askForNotifications = rememberNotificationPermissionRequest()
    AudioDialogs(
        title = loaded?.article?.title.orEmpty(),
        audioState = audioState,
        listenState = listenState,
        actions = audioActions,
        showCreateAudio = showCreateAudio,
        onCreateAudio = { voiceId ->
            askForNotifications()
            audioActions.onCreate(voiceId)
            showCreateAudio = false
        },
        onDismissCreateAudio = { showCreateAudio = false },
        showPlayer = showPlayer,
        onDismissPlayer = { showPlayer = false },
        confirmDeleteAudio = confirmDeleteAudio,
        onAskDeleteAudio = { confirmDeleteAudio = true },
        onDismissDeleteAudio = { confirmDeleteAudio = false },
        onDeleteAudio = {
            confirmDeleteAudio = false
            showPlayer = false
            audioActions.onDelete()
        },
    )
    if (confirmTrash) {
        TrashAudioDialog(
            onConfirm = {
                confirmTrash = false
                onAction(ArticleAction.MoveToTrash)
            },
            onDismiss = { confirmTrash = false },
        )
    }
    // Moving an article with audio to Trash deletes the audio, so it asks first.
    val onArticleAction = { action: ArticleAction ->
        if (action == ArticleAction.MoveToTrash && audioState.hasAudio) confirmTrash = true else onAction(action)
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val title = loaded?.article?.title.orEmpty()
    val body = remember(loaded?.body, title) { readerBody(loaded?.body.orEmpty(), title) }
    val sections = remember(body) { sections(body) }
    val currentSection by remember(sections) {
        derivedStateOf {
            // Offsets count from below the top bar; items scrolled under it have negative offsets.
            val topItem =
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.offset + it.size > 0 }
                    ?.index ?: listState.firstVisibleItemIndex
            sections.lastOrNull { it.itemIndex <= topItem }
        }
    }
    var showContents by rememberSaveable { mutableStateOf(false) }
    // A short article has nothing to jump between.
    val onShowContents = if (sections.size >= 2) ({ showContents = true }) else null
    if (showContents && onShowContents != null) {
        ContentsSheet(
            title = title,
            sections = sections,
            current = currentSection,
            onSelect = { itemIndex -> scope.launch { listState.scrollToItem(itemIndex) } },
            onDismiss = { showContents = false },
        )
    }
    Scaffold(
        modifier = modifier,
        topBar = { ArticleTopBar(onBack, sectionTitle = currentSection?.title, onShowContents = onShowContents) },
        bottomBar = {
            if (loaded != null) {
                Column {
                    AudioDock(
                        title = loaded.article.title,
                        audioState = audioState,
                        listenState = listenState,
                        actions = audioActions,
                        onExpand = { showPlayer = true },
                    )
                    ArticleBottomBar(
                        state = loaded,
                        audioState = audioState,
                        listenState = listenState,
                        onAction = onArticleAction,
                        onShare = onShare,
                        onOpenOriginal = onOpenOriginal,
                        onCreateAudio = { showCreateAudio = true },
                        onListen = audioActions.onListen,
                    )
                }
            }
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
                ArticleContent(
                    article = uiState.article,
                    body = body,
                    listState = listState,
                    innerPadding = innerPadding,
                    onOpenOriginal = onOpenOriginal,
                    onOpenLink = onOpenLink,
                    audioState = audioState,
                    listenState = listenState,
                    audioActions = audioActions,
                )
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

/**
 * The back button and, once the reader is inside the body, the section being read. With
 * [onShowContents], the section name and a Contents button open the list of sections.
 *
 * TopAppBar is still experimental in Material 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleTopBar(
    onBack: () -> Unit,
    sectionTitle: String?,
    onShowContents: (() -> Unit)?,
) {
    TopAppBar(
        title = {
            if (sectionTitle != null) {
                Text(
                    text = sectionTitle,
                    modifier = if (onShowContents != null) Modifier.clickable(onClick = onShowContents) else Modifier,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        actions = {
            if (onShowContents != null) {
                IconButton(onClick = onShowContents) {
                    Icon(
                        painter = painterResource(R.drawable.ic_toc),
                        contentDescription = stringResource(R.string.article_contents_open),
                    )
                }
            }
        },
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

/**
 * The shelf changes for the article, Share, and the main action: Create audio or Listen when the
 * server makes audio, otherwise Open original. When audio takes the main button, Open original
 * moves among the icons.
 */
@Composable
private fun ArticleBottomBar(
    state: ArticleUiState.Loaded,
    audioState: AudioUiState,
    listenState: ListenState,
    onAction: (ArticleAction) -> Unit,
    onShare: (Article) -> Unit,
    onOpenOriginal: (url: String) -> Unit,
    onCreateAudio: () -> Unit,
    onListen: () -> Unit,
) {
    val article = state.article
    // An article the agent wrote without a source has no original to open.
    val url = article.url
    val audioMainAction =
        when {
            article.shelf == Shelf.Trash -> null
            audioState is AudioUiState.NotCreated -> AudioMainAction.Create
            audioState is AudioUiState.Ready && !listenState.isLoaded -> AudioMainAction.Listen
            else -> null
        }
    val audioInUse = audioMainAction != null || audioState.hasAudio
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
            if (audioInUse && url != null) {
                IconButton(onClick = { onOpenOriginal(url) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_open_in_new),
                        contentDescription = stringResource(R.string.action_open_original),
                    )
                }
            }
        },
        floatingActionButton = {
            if (audioMainAction != null) {
                val create = audioMainAction == AudioMainAction.Create
                val label = stringResource(if (create) R.string.audio_create else R.string.audio_listen)
                ExtendedFloatingActionButton(
                    text = { Text(label) },
                    icon = {
                        Icon(
                            painter = painterResource(if (create) R.drawable.ic_waveform else R.drawable.ic_headphones),
                            contentDescription = label,
                        )
                    },
                    onClick = if (create) onCreateAudio else onListen,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                )
            } else if (url != null && !audioInUse) {
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

/** A heading in the reader's body, with the index of its item in the reader's list. */
internal data class Section(
    val title: String,
    val level: Int,
    val itemIndex: Int,
)

/** The reader's list shows the cover and the header before the body. */
private const val ITEMS_BEFORE_BODY = 2

/** Headings deeper than this are too fine-grained for the list of sections. */
private const val MAX_SECTION_LEVEL = 3

/** [body] without a first heading that only repeats the [title] the header already shows. */
internal fun readerBody(
    body: List<BodyBlock>,
    title: String,
): List<BodyBlock> {
    val first = body.firstOrNull() as? BodyBlock.Heading ?: return body
    val repeatsTitle =
        first.level == 1 &&
            first.text.text
                .trim()
                .equals(title.trim(), ignoreCase = true)
    return if (repeatsTitle) body.drop(1) else body
}

/** The headings of [body] that the list of sections offers. */
internal fun sections(body: List<BodyBlock>): List<Section> =
    body.mapIndexedNotNull { index, block ->
        (block as? BodyBlock.Heading)?.takeIf { it.level <= MAX_SECTION_LEVEL }?.let {
            Section(
                title =
                    it.text.text
                        .replace('\n', ' ')
                        .trim(),
                level = it.level,
                itemIndex = ITEMS_BEFORE_BODY + index,
            )
        }
    }

/**
 * The article's sections; choosing one calls [onSelect] with its list item and closes the sheet.
 * ModalBottomSheet is still experimental in Material 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(
    title: String,
    sections: List<Section>,
    current: Section?,
    onSelect: (itemIndex: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Fully open, so the highlighted section is on screen rather than below a half-open sheet.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val topLevel = sections.minOf { it.level }
    val choose = { itemIndex: Int ->
        onSelect(itemIndex)
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
        Unit
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = stringResource(R.string.article_contents),
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        // Opens on the section being read, with a couple of rows of context above it.
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, sections.indexOf(current) + 1 - 2))
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ContentsRow(text = title, depth = 0, selected = current == null, onClick = { choose(0) })
            }
            items(sections) { section ->
                ContentsRow(
                    text = section.title,
                    depth = section.level - topLevel,
                    selected = section == current,
                    onClick = { choose(section.itemIndex) },
                )
            }
        }
    }
}

@Composable
private fun ContentsRow(
    text: String,
    depth: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val style = if (depth == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge
    Text(
        text = text,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 24.dp + 20.dp * depth, end = 24.dp, top = 12.dp, bottom = 12.dp),
        color =
            when {
                selected -> MaterialTheme.colorScheme.primary
                depth == 0 -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        fontWeight = if (selected) FontWeight.Bold else null,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = style,
    )
}

@Composable
private fun ArticleContent(
    article: Article,
    body: List<BodyBlock>,
    listState: LazyListState,
    innerPadding: PaddingValues,
    onOpenOriginal: (url: String) -> Unit,
    onOpenLink: (url: String) -> Unit,
    audioState: AudioUiState,
    listenState: ListenState,
    audioActions: AudioActions,
) {
    val layoutDirection = LocalLayoutDirection.current
    val spoken = remember { SpokenView() }
    val following = followSpokenWords(listState, listenState, spoken, innerPadding)
    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { spoken.screen = it }) {
        ArticleList(
            article,
            body,
            listState,
            innerPadding,
            onOpenOriginal,
            onOpenLink,
            audioState,
            listenState,
            audioActions,
            layoutDirection,
            onSpokenPlaced = { block, placement ->
                spoken.block = block
                spoken.placement = placement
            },
        )
        if (!following.value && listenState.isPlaying && listenState.highlight != null) {
            BackToVoiceChip(
                onClick = { following.value = true },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = innerPadding.calculateBottomPadding() + 12.dp),
            )
        }
    }
}

/**
 * Where the reader and the block the voice is reading are on screen, as last laid out. Plain
 * fields, not state: following reads them when the voice moves on, and they change every frame of
 * a scroll.
 */
private class SpokenView {
    var screen: LayoutCoordinates? = null
    var block: Int = -1
    var placement: SpokenPlacement? = null

    /** Where [highlight]'s words are, in root coordinates; null while its block is off screen. */
    fun boundsOf(highlight: SpokenHighlight): ClosedFloatingPointRange<Float>? =
        placement?.takeIf { block == highlight.block }?.boundsInRoot(highlight.sentence)
}

/**
 * Keeps the words the voice is reading in view while it plays, like turning a page: once the
 * sentence reaches the bottom of the screen, or is above the top, it scrolls up to near the top.
 * Dragging the article stops that, so the reader can look elsewhere; the returned value turns it
 * back on (the "Back to the voice" chip), as does pressing play.
 */
@Composable
private fun followSpokenWords(
    listState: LazyListState,
    listenState: ListenState,
    spoken: SpokenView,
    innerPadding: PaddingValues,
): MutableState<Boolean> {
    val following = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // Only the reader's own drags; scrolling for the voice is not an interaction.
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) following.value = false }
    }
    LaunchedEffect(listenState.isPlaying) {
        if (listenState.isPlaying) following.value = true
    }
    val density = LocalDensity.current
    val topPx = with(density) { innerPadding.calculateTopPadding().toPx() }
    val bottomPx = with(density) { innerPadding.calculateBottomPadding().toPx() + FOLLOW_BOTTOM_MARGIN.toPx() }
    val highlight = listenState.highlight
    LaunchedEffect(highlight, following.value, listenState.isPlaying) {
        if (highlight == null || !following.value || !listenState.isPlaying) return@LaunchedEffect
        val screen = spoken.screen?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val visibleTop = screen.positionInRoot().y + topPx
        val visibleBottom = screen.positionInRoot().y + screen.size.height - bottomPx
        if (visibleBottom <= visibleTop) return@LaunchedEffect
        var words = spoken.boundsOf(highlight)
        if (words == null) {
            // The block is off screen: bring it to the top, then place its sentence once laid out.
            listState.animateScrollToItem(ITEMS_BEFORE_BODY + highlight.block)
            withFrameNanos {}
            words = spoken.boundsOf(highlight) ?: return@LaunchedEffect
        }
        if (words.start >= visibleTop && words.endInclusive <= visibleBottom) return@LaunchedEffect
        val anchor = visibleTop + (visibleBottom - visibleTop) * FOLLOW_ANCHOR_FRACTION
        listState.animateScrollBy(words.start - anchor)
    }
    return following
}

/** Where a sentence that ran off the screen is brought back to: this far down the visible area. */
private const val FOLLOW_ANCHOR_FRACTION = 0.15f

/** How close to the bars at the bottom a sentence may come before the page turns. */
private val FOLLOW_BOTTOM_MARGIN = 12.dp

@Composable
private fun ArticleList(
    article: Article,
    body: List<BodyBlock>,
    listState: LazyListState,
    innerPadding: PaddingValues,
    onOpenOriginal: (url: String) -> Unit,
    onOpenLink: (url: String) -> Unit,
    audioState: AudioUiState,
    listenState: ListenState,
    audioActions: AudioActions,
    layoutDirection: LayoutDirection,
    onSpokenPlaced: (block: Int, SpokenPlacement) -> Unit,
) {
    val spokenBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = SPOKEN_BLOCK_ALPHA)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
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
        item {
            ArticleHeader(
                article = article,
                audioState = audioState,
                listenState = listenState,
                audioActions = audioActions,
                modifier = Modifier.padding(start = 24.dp, top = 6.dp, end = 24.dp),
            )
        }
        val url = article.url
        if (body.isEmpty() && url != null) {
            item {
                NoSavedBody(onOpenOriginal = { onOpenOriginal(url) }, modifier = Modifier.padding(horizontal = 24.dp))
            }
        } else {
            itemsIndexed(body) { index, block ->
                val spoken = listenState.highlight?.takeIf { it.block == index }
                // The block being read gets a soft background; its text stays where it was.
                val placement =
                    if (spoken == null) {
                        Modifier.padding(horizontal = 24.dp)
                    } else {
                        Modifier
                            .padding(horizontal = 14.dp)
                            .background(spokenBackground, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    }
                BodyBlockView(
                    block,
                    onLinkClick = onOpenLink,
                    modifier = placement,
                    spokenSentence = spoken?.sentence,
                    onSpokenPlaced = spoken?.let { { placed -> onSpokenPlaced(index, placed) } },
                )
            }
        }
    }
}

private const val SPOKEN_BLOCK_ALPHA = 0.3f

@Composable
private fun ArticleHeader(
    article: Article,
    audioState: AudioUiState,
    listenState: ListenState,
    audioActions: AudioActions,
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
        if (audioState is AudioUiState.Ready) {
            ReadyAudioCard(audioState.audio, listenState, onListen = audioActions.onListen, onPause = audioActions.onPause)
        }
        if (audioState.hasAudio) AudioLockLine()
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

/** What the main button does for audio. */
private enum class AudioMainAction { Create, Listen }

/** The audio card docked above the bottom bar: a running job, a failure, or the mini player. */
@Composable
private fun AudioDock(
    title: String,
    audioState: AudioUiState,
    listenState: ListenState,
    actions: AudioActions,
    onExpand: () -> Unit,
) {
    when (audioState) {
        is AudioUiState.Generating -> GeneratingCard(audioState, actions.onCancel)
        is AudioUiState.Downloading -> DownloadingCard(audioState)
        is AudioUiState.Failed -> FailedCard(audioState, onRetry = actions.onRetry, onDelete = actions.onDelete)
        is AudioUiState.Ready -> if (listenState.isLoaded) MiniPlayer(title, audioState.audio.voice.name, listenState, actions, onExpand)
        else -> Unit
    }
}

/** The audio sheets and dialogs the reader can show. */
@Composable
private fun AudioDialogs(
    title: String,
    audioState: AudioUiState,
    listenState: ListenState,
    actions: AudioActions,
    showCreateAudio: Boolean,
    onCreateAudio: (voiceId: String) -> Unit,
    onDismissCreateAudio: () -> Unit,
    showPlayer: Boolean,
    onDismissPlayer: () -> Unit,
    confirmDeleteAudio: Boolean,
    onAskDeleteAudio: () -> Unit,
    onDismissDeleteAudio: () -> Unit,
    onDeleteAudio: () -> Unit,
) {
    if (showCreateAudio && audioState is AudioUiState.NotCreated && audioState.voices.isNotEmpty()) {
        CreateAudioSheet(title, audioState, onCreate = onCreateAudio, onPlaySample = actions.onPlaySample, onDismiss = onDismissCreateAudio)
    }
    val ready = audioState as? AudioUiState.Ready
    if (showPlayer && ready != null && listenState.isLoaded) {
        PlayerSheet(title, ready.audio, listenState, actions, onDeleteAudio = onAskDeleteAudio, onDismiss = onDismissPlayer)
    }
    if (confirmDeleteAudio && ready != null) {
        DeleteAudioDialog(ready.audio, onConfirm = onDeleteAudio, onDismiss = onDismissDeleteAudio)
    }
}

@Composable
private fun AudioMessageSnackbar(
    message: AudioMessage,
    snackbarHostState: SnackbarHostState,
    onMessageShown: (AudioMessage) -> Unit,
) {
    val text = audioMessageText(message)
    val currentOnMessageShown by rememberUpdatedState(onMessageShown)
    LaunchedEffect(message) {
        snackbarHostState.showSnackbar(text)
        currentOnMessageShown(message)
    }
}

/**
 * Asks once for permission to post notifications, which says when audio is ready. Android 12 and
 * older need no permission. Creating the audio goes ahead whatever the answer.
 */
@Composable
private fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
