package dev.vvivid.newsfeed.data

import kotlinx.coroutines.CompletableDeferred
import java.time.Duration
import java.time.Instant

/**
 * An in-memory [NewsRepository] that follows the server's article rules: lists come in pages of
 * [pageSize], every real move raises the revision, a stale expected revision is a conflict, Trash
 * keeps the archive state and is only found when asked for, and repeating the current state
 * changes nothing.
 */
class FakeNewsRepository(
    articles: List<Article> = emptyList(),
    private val bodies: Map<String, String> = emptyMap(),
    private val pageSize: Int = 50,
) : NewsRepository {
    private val articles = articles.associateBy { it.id }.toMutableMap()
    private val heldShelves = mutableMapOf<Shelf, CompletableDeferred<Unit>>()
    private var heldWrites: CompletableDeferred<Unit>? = null

    /** The time each move is stamped with; it advances by a minute per move. */
    private var clock: Instant = TestNow

    /** While set, every call fails with this exception, like an unreachable server. */
    var failure: Exception? = null

    /** The shelves listed so far, one entry per page, in call order. */
    val listedShelves = mutableListOf<Shelf>()

    /** The writes (moves) received so far, failed ones included. */
    var writeCount = 0
        private set

    /** The current server-side copy of an article. */
    fun article(id: String): Article = articles.getValue(id)

    /** Changes an article as another client would, such as the server's web reader. */
    fun replace(article: Article) {
        articles[article.id] = article
    }

    /** Makes listing [shelf] wait until [release] is called, like a slow server. */
    fun hold(shelf: Shelf) {
        heldShelves[shelf] = CompletableDeferred()
    }

    fun release(shelf: Shelf) {
        heldShelves.remove(shelf)?.complete(Unit)
    }

    /** Makes writes wait until [releaseWrites] is called, like a slow server. */
    fun holdWrites() {
        heldWrites = CompletableDeferred()
    }

    fun releaseWrites() {
        heldWrites?.complete(Unit)
        heldWrites = null
    }

    /** Cursors are the index of the page's first article, prefixed with the shelf they belong to. */
    override suspend fun listArticles(
        shelf: Shelf,
        cursor: String?,
    ): ArticlePage {
        listedShelves += shelf
        heldShelves[shelf]?.await()
        failIfRequested()
        val start = cursor?.let { it.removePrefix("${shelf.name}:").toInt() } ?: 0
        val onShelf = articles.values.filter { it.shelf == shelf }.sortedWith(ShelfOrder)
        val end = minOf(start + pageSize, onShelf.size)
        val nextCursor = if (end < onShelf.size) "${shelf.name}:$end" else null
        return ArticlePage(onShelf.subList(minOf(start, end), end), nextCursor)
    }

    override suspend fun getArticle(
        id: String,
        inTrash: Boolean,
    ): ArticleDetail {
        failIfRequested()
        val article = articles[id]?.takeIf { (it.shelf == Shelf.Trash) == inTrash } ?: throw ArticleNotFoundException()
        return ArticleDetail(article, bodies[id])
    }

    override suspend fun setArchived(
        id: String,
        expectedRevision: Long,
        archived: Boolean,
    ): Article {
        val current = beginWrite(id, expectedRevision)
        if (current.trashedAt != null) throw ArticleNotFoundException()
        if ((current.archivedAt != null) == archived) return current
        return save(current.copy(revision = current.revision + 1, archivedAt = if (archived) tick() else null))
    }

    override suspend fun moveToTrash(
        id: String,
        expectedRevision: Long,
    ): Article {
        val current = beginWrite(id, expectedRevision)
        if (current.trashedAt != null) throw ArticleNotFoundException()
        return save(current.copy(revision = current.revision + 1, trashedAt = tick()))
    }

    override suspend fun restoreFromTrash(
        id: String,
        expectedRevision: Long,
    ): Article {
        val current = beginWrite(id, expectedRevision)
        if (current.trashedAt == null) return current
        return save(current.copy(revision = current.revision + 1, trashedAt = null))
    }

    /**
     * Every write starts here: it counts the write, waits while writes are held and may fail like
     * the server. It returns the stored article if it is still at [expectedRevision].
     */
    private suspend fun beginWrite(
        id: String,
        expectedRevision: Long,
    ): Article {
        writeCount++
        heldWrites?.await()
        failIfRequested()
        val current = articles[id] ?: throw ArticleNotFoundException()
        if (current.revision != expectedRevision) throw ArticleConflictException()
        return current
    }

    private fun save(article: Article): Article {
        articles[article.id] = article
        return article
    }

    private fun tick(): Instant {
        clock += Duration.ofMinutes(1)
        return clock
    }

    private fun failIfRequested() {
        failure?.let { throw it }
    }
}
