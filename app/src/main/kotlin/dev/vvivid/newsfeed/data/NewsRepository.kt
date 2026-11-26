package dev.vvivid.newsfeed.data

/**
 * The user's articles on their AgentNewsfeed server. Implementations are main-safe and throw when
 * a call fails; screens show the failure and let the user try again. Failures the screens treat
 * differently are [NewsServerException]s.
 *
 * The writes are the server's article state changes. Each sends the [Article.revision] the caller
 * last saw, fails with [ArticleConflictException] if the article has changed since, and returns the
 * article as it is afterwards. Each call is one user intent, so it gets its own idempotency key;
 * trying the same change again after its outcome was unknown reuses that key, so the server applies
 * it at most once.
 */
interface NewsRepository {
    /**
     * One page of [shelf] in [ShelfOrder]: the first page, or the page that follows the one whose
     * [ArticlePage.nextCursor] is [cursor].
     */
    suspend fun listArticles(
        shelf: Shelf,
        cursor: String? = null,
    ): ArticlePage

    /** One article with its saved body. An article in Trash is only found with [inTrash]. */
    suspend fun getArticle(
        id: String,
        inTrash: Boolean,
    ): ArticleDetail

    /** Archives an Active article ([archived] true) or returns an archived one to Active. */
    suspend fun setArchived(
        id: String,
        expectedRevision: Long,
        archived: Boolean,
    ): Article

    /** Moves an article to Trash. It keeps its content and whether it was archived. */
    suspend fun moveToTrash(
        id: String,
        expectedRevision: Long,
    ): Article

    /** Takes an article out of Trash, back to Active or Archive, wherever it was before. */
    suspend fun restoreFromTrash(
        id: String,
        expectedRevision: Long,
    ): Article
}

/**
 * Consecutive articles of one shelf.
 *
 * @property nextCursor continues the shelf after [articles]; null when they reach its end. The
 *   server binds a cursor to its shelf, so it is only passed back with the same shelf.
 */
data class ArticlePage(
    val articles: List<Article>,
    val nextCursor: String?,
)
