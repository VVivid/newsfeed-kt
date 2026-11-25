package dev.vvivid.newsfeed.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvivid.newsfeed.data.Article
import dev.vvivid.newsfeed.data.NewsRepository
import dev.vvivid.newsfeed.data.Shelf
import dev.vvivid.newsfeed.data.resultOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the article screen shows. */
sealed interface ArticleUiState {
    data object Loading : ArticleUiState

    /** Loading failed; there is nothing to show but a retry. */
    data object LoadFailed : ArticleUiState

    /**
     * @property body the saved body, or empty when the agent saved only the summary.
     * @property isUpdating a change is being written, so no other change can start.
     * @property message a snackbar message waiting to be shown.
     */
    data class Loaded(
        val article: Article,
        val body: List<BodyBlock>,
        val isUpdating: Boolean = false,
        val message: ArticleMessage? = null,
    ) : ArticleUiState
}

/** A change the reader's bottom bar can make to the article. */
enum class ArticleAction {
    /** Active to Archive. */
    Archive,

    /** Archive to Active. */
    Unarchive,

    /** Active or Archive to Trash. */
    MoveToTrash,

    /** Trash back to Active or Archive, wherever the article was. */
    Restore,
}

/** The changes offered for an article on [shelf]. In Trash, the only change is Restore. */
fun articleActions(shelf: Shelf): List<ArticleAction> =
    when (shelf) {
        Shelf.Active -> listOf(ArticleAction.Archive, ArticleAction.MoveToTrash)
        Shelf.Archive -> listOf(ArticleAction.Unarchive, ArticleAction.MoveToTrash)
        Shelf.Trash -> listOf(ArticleAction.Restore)
    }

/** A snackbar message about a change. */
sealed interface ArticleMessage {
    data class Done(
        val action: ArticleAction,
    ) : ArticleMessage

    data class Failed(
        val action: ArticleAction,
    ) : ArticleMessage
}

/**
 * Loads one article with its body and makes the changes the reader offers.
 *
 * @param inTrash whether the article was opened from Trash; the server only finds a trashed
 *   article when asked to look there.
 * @param parsingDispatcher where the Markdown body is parsed, away from the main thread.
 */
class ArticleViewModel(
    private val articleId: String,
    private val inTrash: Boolean,
    private val repository: NewsRepository,
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ArticleUiState>(ArticleUiState.Loading)
    val uiState: StateFlow<ArticleUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun retry() {
        if (_uiState.value != ArticleUiState.LoadFailed) return
        _uiState.value = ArticleUiState.Loading
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val loaded =
                resultOrNull {
                    val detail = repository.getArticle(articleId, inTrash)
                    val body =
                        detail.contentMarkdown?.let { markdown ->
                            withContext(parsingDispatcher) { parseArticleBody(markdown, detail.article.url) }
                        }
                    ArticleUiState.Loaded(detail.article, body.orEmpty())
                }
            _uiState.value = loaded ?: ArticleUiState.LoadFailed
        }
    }

    /**
     * Makes [action] on the server and shows the article as the server returns it. Ignored while
     * another change is running, because each change needs the revision the previous one returns.
     */
    fun perform(action: ArticleAction) {
        val loaded = _uiState.value as? ArticleUiState.Loaded ?: return
        if (loaded.isUpdating || action !in articleActions(loaded.article.shelf)) return

        _uiState.value = loaded.copy(isUpdating = true)
        viewModelScope.launch {
            val article = loaded.article
            val updated =
                resultOrNull {
                    when (action) {
                        ArticleAction.Archive -> repository.setArchived(article.id, article.revision, archived = true)
                        ArticleAction.Unarchive -> repository.setArchived(article.id, article.revision, archived = false)
                        ArticleAction.MoveToTrash -> repository.moveToTrash(article.id, article.revision)
                        ArticleAction.Restore -> repository.restoreFromTrash(article.id, article.revision)
                    }
                }
            _uiState.update { state ->
                val current = state as? ArticleUiState.Loaded ?: return@update state
                if (updated != null) {
                    current.copy(article = updated, isUpdating = false, message = ArticleMessage.Done(action))
                } else {
                    current.copy(isUpdating = false, message = ArticleMessage.Failed(action))
                }
            }
        }
    }

    /** The snackbar for [message] closed. A newer message that replaced it stays. */
    fun messageShown(message: ArticleMessage) {
        _uiState.update { state ->
            if (state is ArticleUiState.Loaded && state.message == message) state.copy(message = null) else state
        }
    }
}
