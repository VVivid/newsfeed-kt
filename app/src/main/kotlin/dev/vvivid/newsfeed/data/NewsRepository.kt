package dev.vvivid.newsfeed.data

/**
 * The user's articles on their AgentNewsfeed server. Implementations are main-safe and throw when
 * a call fails; screens show the failure and let the user try again.
 *
 * The writes are the server's article state changes. Each sends the [Article.revision] the caller
 * last saw, fails if the article has changed since, and returns the article as it is afterwards.
 * Each call is one user intent, so it gets its own idempotency key.
 *
 * This version has no implementation that talks to a server: only tests provide one.
 */
interface NewsRepository {
    /** The newest articles on [shelf], in [ShelfOrder]. */
    suspend fun listArticles(shelf: Shelf): List<Article>

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
