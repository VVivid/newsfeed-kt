package dev.vvivid.newsfeed.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvivid.newsfeed.data.Article
import dev.vvivid.newsfeed.data.NewsRepository
import dev.vvivid.newsfeed.data.Shelf
import dev.vvivid.newsfeed.data.ShelfOrder
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
)

/** The articles of the selected shelf. */
sealed interface ShelfContent {
    /** The shelf's first load is running; there is nothing to show yet. */
    data object Loading : ShelfContent

    /** The shelf's first load failed; there is nothing to show but a retry. */
    data object LoadFailed : ShelfContent

    /**
     * The shelf's articles in [ShelfOrder]; empty when the shelf has none.
     *
     * @property isRefreshing a reload is running while these articles stay on screen.
     * @property isStale the last reload failed, so these articles may be out of date.
     */
    data class Loaded(
        val articles: List<Article>,
        val isRefreshing: Boolean = false,
        val isStale: Boolean = false,
    ) : ShelfContent
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
     * [move] failed: the server rejected it or could not be reached. The article is back on
     * Active.
     */
    data class MoveFailed(
        val move: ArticleMove,
    ) : NewsMessage

    /** Undoing [move] failed, so the article stays where the move put it. */
    data class UndoFailed(
        val move: ArticleMove,
    ) : NewsMessage
}

/**
 * Loads the selected shelf from [repository] and carries out swipes on Active articles.
 *
 * A swipe shows its result before the server confirms it:
 * 1. [archive] or [moveToTrash] takes the article out of the list at once and asks the server to
 *    move it.
 * 2. If the server moves it, [NewsUiState.message] becomes [NewsMessage.Moved], whose snackbar
 *    offers Undo. If the move fails, the article goes back where it was and the message is
 *    [NewsMessage.MoveFailed].
 * 3. [undo] asks the server to move the article back to Active. If that fails, the article stays
 *    where the move put it and the message is [NewsMessage.UndoFailed].
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

    init {
        load(Shelf.Active)
    }

    fun selectShelf(shelf: Shelf) {
        if (shelf == _uiState.value.shelf) return
        _uiState.update { it.copy(shelf = shelf, content = ShelfContent.Loading) }
        load(shelf)
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
                    is ShelfContent.Loaded -> current.copy(isRefreshing = true)
                    ShelfContent.Loading, ShelfContent.LoadFailed -> ShelfContent.Loading
                }
            state.copy(content = content)
        }
        load(_uiState.value.shelf)
    }

    private fun load(shelf: Shelf) {
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                val articles = resultOrNull { repository.listArticles(shelf) }
                _uiState.update { state ->
                    val content =
                        when {
                            articles != null -> ShelfContent.Loaded(articles)

                            // Keep what is on screen rather than replacing it with an error.
                            state.content is ShelfContent.Loaded -> state.content.copy(isRefreshing = false, isStale = true)

                            else -> ShelfContent.LoadFailed
                        }
                    state.copy(content = content)
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
            val moved =
                resultOrNull {
                    when (move) {
                        ArticleMove.Archive -> repository.setArchived(article.id, article.revision, archived = true)
                        ArticleMove.Trash -> repository.moveToTrash(article.id, article.revision)
                    }
                }
            _uiState.update { state ->
                if (moved != null) {
                    state.withArticlePlaced(moved).copy(message = NewsMessage.Moved(moved, move))
                } else {
                    state.withArticlePlaced(article).copy(message = NewsMessage.MoveFailed(move))
                }
            }
        }
    }

    /** Snackbar Undo: moves the article of [moved] back to Active. */
    fun undo(moved: NewsMessage.Moved) {
        _uiState.update { it.copy(message = null) }
        viewModelScope.launch {
            val article = moved.article
            val restored =
                resultOrNull {
                    when (moved.move) {
                        ArticleMove.Archive -> repository.setArchived(article.id, article.revision, archived = false)
                        ArticleMove.Trash -> repository.restoreFromTrash(article.id, article.revision)
                    }
                }
            _uiState.update { state ->
                if (restored != null) {
                    state.withArticlePlaced(restored)
                } else {
                    state.copy(message = NewsMessage.UndoFailed(moved.move))
                }
            }
        }
    }

    /** The snackbar for [message] closed. A newer message that replaced it stays. */
    fun messageShown(message: NewsMessage) {
        _uiState.update { if (it.message == message) it.copy(message = null) else it }
    }
}

private fun NewsUiState.withArticleRemoved(articleId: String): NewsUiState {
    val loaded = content as? ShelfContent.Loaded ?: return this
    return copy(content = loaded.copy(articles = loaded.articles.filterNot { it.id == articleId }))
}

/**
 * Puts [article] where it now belongs: into the shown list at its place in [ShelfOrder] if it is on
 * the shown shelf, and out of the list otherwise.
 */
private fun NewsUiState.withArticlePlaced(article: Article): NewsUiState {
    val loaded = content as? ShelfContent.Loaded ?: return this
    val others = loaded.articles.filterNot { it.id == article.id }
    if (article.shelf != shelf) return copy(content = loaded.copy(articles = others))

    // Just before the first article that sorts after it; at the end if none does.
    val index = others.indexOfFirst { ShelfOrder.compare(article, it) < 0 }.takeIf { it >= 0 } ?: others.size
    val articles = others.toMutableList().apply { add(index, article) }
    return copy(content = loaded.copy(articles = articles))
}
