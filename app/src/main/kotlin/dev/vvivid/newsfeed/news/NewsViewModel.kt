package dev.vvivid.newsfeed.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvivid.newsfeed.data.Article
import dev.vvivid.newsfeed.data.ArticlePage
import dev.vvivid.newsfeed.data.NewsRepository
import dev.vvivid.newsfeed.data.PermissionDeniedException
import dev.vvivid.newsfeed.data.Shelf
import dev.vvivid.newsfeed.data.ShelfOrder
import dev.vvivid.newsfeed.data.TagCount
import dev.vvivid.newsfeed.data.isArticleChangedOnServer
import dev.vvivid.newsfeed.data.resultOf
import dev.vvivid.newsfeed.data.resultOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the News screen shows. */
data class NewsUiState(
    val shelf: Shelf = Shelf.Active,
    val content: ShelfContent = ShelfContent.Loading,
    /** A snackbar message waiting to be shown. */
    val message: NewsMessage? = null,
    /** The tags in use on the shown shelf, most used first; empty until they load. */
    val tags: List<TagCount> = emptyList(),
    /**
     * The tag filter: when not empty, the shelf shows only articles having at least one of these
     * tags. It stays when the user switches shelves.
     */
    val selectedTags: Set<String> = emptySet(),
) {
    /** The tags to offer as filters: those in use, then selected ones the shelf no longer has. */
    val filterTags: List<String>
        get() = tags.map { it.tag }.let { inUse -> inUse + selectedTags.filterNot(inUse::contains).sorted() }
}

/** The articles of the selected shelf. */
sealed interface ShelfContent {
    /** The shelf's first load is running; there is nothing to show yet. */
    data object Loading : ShelfContent

    /** The shelf's first load failed; there is nothing to show but a retry. */
    data object LoadFailed : ShelfContent

    /**
     * The shelf's articles loaded so far, in [ShelfOrder]; empty when the shelf has none.
     *
     * @property nextCursor continues the shelf after [articles]; null when they are all of it.
     * @property isRefreshing a reload is running while these articles stay on screen.
     * @property isStale the last reload failed, so these articles may be out of date.
     * @property isLoadingMore the next page is loading.
     * @property loadMoreFailed loading the next page failed; it can be tried again.
     */
    data class Loaded(
        val articles: List<Article>,
        val nextCursor: String? = null,
        val isRefreshing: Boolean = false,
        val isStale: Boolean = false,
        val isLoadingMore: Boolean = false,
        val loadMoreFailed: Boolean = false,
    ) : ShelfContent {
        val hasMore: Boolean
            get() = nextCursor != null
    }
}

/** What a swipe on an Active article does. */
enum class ArticleMove { Archive, Trash }

/** A snackbar message about a swipe or its undo. */
sealed interface NewsMessage {
    /**
     * The server carried out [move]. [article] is the article as the server returned it, so Undo
     * sends its new revision as the expected one. The snackbar offers Undo.
     */
    data class Moved(
        val article: Article,
        val move: ArticleMove,
    ) : NewsMessage

    /**
     * [move] failed: the server could not be reached or did not answer usefully. The article is
     * back on Active.
     */
    data class MoveFailed(
        val move: ArticleMove,
    ) : NewsMessage

    /** Undoing [move] failed, so the article stays where the move put it. */
    data class UndoFailed(
        val move: ArticleMove,
    ) : NewsMessage

    /**
     * A move or an undo found that the article had changed on the server, for example in the web
     * reader, so the server refused it. The shelf is reloaded to show the server's state.
     */
    data object ChangedOnServer : NewsMessage

    /** The server refused a move or an undo because the sign-in does not allow changes. */
    data object ChangeNotAllowed : NewsMessage
}

/**
 * Loads the selected shelf from [repository], page by page, and carries out swipes on Active
 * articles.
 *
 * A swipe shows its result before the server confirms it:
 * 1. [archive] or [moveToTrash] takes the article out of the list at once and asks the server to
 *    move it.
 * 2. If the server moves it, [NewsUiState.message] becomes [NewsMessage.Moved], whose snackbar
 *    offers Undo. If the move fails, the article goes back where it was and the message says why.
 *    If the server refused it because the article had changed, the shelf is reloaded instead.
 * 3. [undo] asks the server to move the article back to Active. If that fails, the article stays
 *    where the move put it and the message says so.
 *
 * Each article the server returns from a move or an undo is placed by its new shelf: into the
 * shown list if it belongs there, out of it otherwise. This keeps the shown shelf consistent with
 * the server without a reload.
 */
class NewsViewModel(
    private val repository: NewsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NewsUiState())
    val uiState: StateFlow<NewsUiState> = _uiState.asStateFlow()

    /** The running load of the shown shelf. A newer load cancels it, so its result is dropped. */
    private var loadJob: Job? = null

    /** The running load of the shown shelf's next page; any newer load cancels it. */
    private var loadMoreJob: Job? = null

    /** The running load of the shown shelf's tags; any newer load cancels it. */
    private var tagsJob: Job? = null

    init {
        load(Shelf.Active)
    }

    fun selectShelf(shelf: Shelf) {
        if (shelf == _uiState.value.shelf) return
        _uiState.update { it.copy(shelf = shelf, content = ShelfContent.Loading, tags = emptyList()) }
        load(shelf)
    }

    /** Adds [tag] to the filter, or takes it out if it is in already, and reloads the shelf. */
    fun toggleTag(tag: String) {
        _uiState.update { state ->
            val selected = if (tag in state.selectedTags) state.selectedTags - tag else state.selectedTags + tag
            state.copy(selectedTags = selected, content = ShelfContent.Loading)
        }
        load(_uiState.value.shelf)
    }

    /** Shows the whole shelf again. */
    fun clearTags() {
        if (_uiState.value.selectedTags.isEmpty()) return
        _uiState.update { it.copy(selectedTags = emptySet(), content = ShelfContent.Loading) }
        load(_uiState.value.shelf)
    }

    /**
     * Loads the shown shelf again: after a failed first load, or to update loaded articles while
     * they stay on screen. Does nothing while a load is already running.
     */
    fun refresh() {
        if (loadJob?.isActive == true) return
        _uiState.update { state ->
            val content =
                when (val current = state.content) {
                    is ShelfContent.Loaded -> current.copy(isRefreshing = true, isLoadingMore = false)
                    ShelfContent.Loading, ShelfContent.LoadFailed -> ShelfContent.Loading
                }
            state.copy(content = content)
        }
        load(_uiState.value.shelf)
    }

    /**
     * Loads [shelf] from its start. A reload fetches at least as many articles as are on screen, so
     * that the list keeps its length and the user keeps their place in it.
     */
    private fun load(shelf: Shelf) {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadTags(shelf)
        val tags = _uiState.value.selectedTags
        val shownCount = (_uiState.value.content as? ShelfContent.Loaded)?.articles?.size ?: 0
        loadJob =
            viewModelScope.launch {
                val page = resultOrNull { loadFromStart(shelf, tags, minimumCount = shownCount) }
                _uiState.update { state ->
                    val content =
                        when {
                            page != null -> ShelfContent.Loaded(page.articles, page.nextCursor)

                            // Keep what is on screen rather than replacing it with an error.
                            state.content is ShelfContent.Loaded -> state.content.copy(isRefreshing = false, isStale = true)

                            else -> ShelfContent.LoadFailed
                        }
                    state.copy(content = content)
                }
            }
    }

    /**
     * The tags of [shelf], for the filter row. If they cannot be loaded the row keeps what it shows;
     * the articles still load.
     */
    private fun loadTags(shelf: Shelf) {
        tagsJob?.cancel()
        tagsJob =
            viewModelScope.launch {
                val tags = resultOrNull { repository.listTags(shelf) } ?: return@launch
                _uiState.update { if (it.shelf == shelf) it.copy(tags = tags) else it }
            }
    }

    /**
     * Pages of [shelf] with [tags] from its start, until there are [minimumCount] articles or the
     * shelf ends.
     */
    private suspend fun loadFromStart(
        shelf: Shelf,
        tags: Set<String>,
        minimumCount: Int,
    ): ArticlePage {
        val articles = mutableListOf<Article>()
        var cursor: String? = null
        do {
            val page = repository.listArticles(shelf, cursor, tags)
            articles.addNew(page.articles)
            cursor = page.nextCursor
        } while (cursor != null && articles.size < minimumCount.coerceAtMost(MAX_RELOADED_ARTICLES))
        return ArticlePage(articles, cursor)
    }

    /**
     * Loads the shown shelf's next page, when the user reaches the end of the loaded articles. Does
     * nothing when there is no next page, or while a load is running.
     */
    fun loadMore() {
        val shelf = _uiState.value.shelf
        val tags = _uiState.value.selectedTags
        val loaded = _uiState.value.content as? ShelfContent.Loaded ?: return
        val cursor = loaded.nextCursor ?: return
        if (loaded.isLoadingMore || loadJob?.isActive == true) return
        _uiState.update { state -> state.withLoaded { it.copy(isLoadingMore = true, loadMoreFailed = false) } }
        loadMoreJob =
            viewModelScope.launch {
                val page = resultOrNull { repository.listArticles(shelf, cursor, tags) }
                _uiState.update { state ->
                    state.withLoaded { content ->
                        if (page == null) {
                            content.copy(isLoadingMore = false, loadMoreFailed = true)
                        } else {
                            val articles = content.articles.toMutableList().apply { addNew(page.articles) }
                            content.copy(articles = articles, nextCursor = page.nextCursor, isLoadingMore = false)
                        }
                    }
                }
            }
    }

    /** Swipe left on an Active article. */
    fun archive(article: Article) = move(article, ArticleMove.Archive)

    /** Swipe right on an Active article. */
    fun moveToTrash(article: Article) = move(article, ArticleMove.Trash)

    private fun move(
        article: Article,
        move: ArticleMove,
    ) {
        // The swiped card disappears at once instead of waiting for the server.
        _uiState.update { it.withArticleRemoved(article.id) }
        viewModelScope.launch {
            val result =
                resultOf {
                    when (move) {
                        ArticleMove.Archive -> repository.setArchived(article.id, article.revision, archived = true)
                        ArticleMove.Trash -> repository.moveToTrash(article.id, article.revision)
                    }
                }
            val moved = result.getOrNull()
            val failure = result.exceptionOrNull()
            _uiState.update { state ->
                when {
                    moved != null -> state.withArticlePlaced(moved).copy(message = NewsMessage.Moved(moved, move))

                    // The swiped article stays out of the list until the reload shows where it is now.
                    failure.isArticleChangedOnServer() -> state.copy(message = NewsMessage.ChangedOnServer)

                    failure is PermissionDeniedException -> state.withArticlePlaced(article).copy(message = NewsMessage.ChangeNotAllowed)

                    else -> state.withArticlePlaced(article).copy(message = NewsMessage.MoveFailed(move))
                }
            }
            if (failure.isArticleChangedOnServer()) refresh()
        }
    }

    /** Snackbar Undo: moves the article of [moved] back to Active. */
    fun undo(moved: NewsMessage.Moved) {
        _uiState.update { it.copy(message = null) }
        viewModelScope.launch {
            val article = moved.article
            val result =
                resultOf {
                    when (moved.move) {
                        ArticleMove.Archive -> repository.setArchived(article.id, article.revision, archived = false)
                        ArticleMove.Trash -> repository.restoreFromTrash(article.id, article.revision)
                    }
                }
            val restored = result.getOrNull()
            val failure = result.exceptionOrNull()
            _uiState.update { state ->
                when {
                    restored != null -> state.withArticlePlaced(restored)
                    failure.isArticleChangedOnServer() -> state.copy(message = NewsMessage.ChangedOnServer)
                    failure is PermissionDeniedException -> state.copy(message = NewsMessage.ChangeNotAllowed)
                    else -> state.copy(message = NewsMessage.UndoFailed(moved.move))
                }
            }
            if (failure.isArticleChangedOnServer()) refresh()
        }
    }

    /** The snackbar for [message] closed. A newer message that replaced it stays. */
    fun messageShown(message: NewsMessage) {
        _uiState.update { if (it.message == message) it.copy(message = null) else it }
    }

    private companion object {
        /** A reload stops after this many articles even if more were on screen; the rest page in again. */
        const val MAX_RELOADED_ARTICLES = 250
    }
}

/** Adds the articles of [page] that are not in this list yet, in their order. */
private fun MutableList<Article>.addNew(page: List<Article>) {
    val known = mapTo(HashSet()) { it.id }
    page.filterTo(this) { known.add(it.id) }
}

private inline fun NewsUiState.withLoaded(change: (ShelfContent.Loaded) -> ShelfContent.Loaded): NewsUiState {
    val loaded = content as? ShelfContent.Loaded ?: return this
    return copy(content = change(loaded))
}

private fun NewsUiState.withArticleRemoved(articleId: String): NewsUiState =
    withLoaded { loaded -> loaded.copy(articles = loaded.articles.filterNot { it.id == articleId }) }

/**
 * Puts [article] where it now belongs: into the shown list at its place in [ShelfOrder] if it is on
 * the shown shelf and passes the tag filter, and out of the list otherwise. An article that sorts after every loaded one
 * belongs to a page that is not loaded yet, unless the shelf has no more pages.
 */
private fun NewsUiState.withArticlePlaced(article: Article): NewsUiState =
    withLoaded { loaded ->
        val others = loaded.articles.filterNot { it.id == article.id }
        // Just before the first article that sorts after it.
        val index = others.indexOfFirst { ShelfOrder.compare(article, it) < 0 }
        val articles =
            when {
                article.shelf != shelf -> others
                selectedTags.isNotEmpty() && article.tags.none(selectedTags::contains) -> others
                index >= 0 -> others.toMutableList().apply { add(index, article) }
                loaded.hasMore -> others
                else -> others + article
            }
        loaded.copy(articles = articles)
    }
