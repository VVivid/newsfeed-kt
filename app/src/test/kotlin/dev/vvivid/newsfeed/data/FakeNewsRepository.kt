package dev.vvivid.newsfeed.data

import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * An in-memory [NewsRepository] that follows the server's article rules: every real move raises
 * the revision, a stale expected revision is rejected, Trash keeps the archive state and is only
 * found when asked for, and repeating the current state changes nothing.
 */
class FakeNewsRepository(
    articles: List<Article> = emptyList(),
    private val bodies: Map<String, String> = emptyMap(),
) : NewsRepository {
    private val articles = articles.associateBy { it.id }.toMutableMap()
    private val heldShelves = mutableMapOf<Shelf, CompletableDeferred<Unit>>()
    private var heldWrites: CompletableDeferred<Unit>? = null

    /** The time each move is stamped with; it advances by a minute per move. */
    private var clock: Instant = TestNow

    /** While set, every call fails with this exception, like an unreachable server. */
    var failure: Exception? = null

    /** The shelves listed so far, in call order. */
    val listedShelves = mutableListOf<Shelf>()

    /** The writes (moves) received so far, failed ones included. */
    var writeCount = 0
        private set

    /** The current server-side copy of an article. */
    fun article(id: String): Article = articles.getValue(id)

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

    override suspend fun listArticles(shelf: Shelf): List<Article> {
        listedShelves += shelf
        heldShelves[shelf]?.await()
        failIfRequested()
        return articles.values.filter { it.shelf == shelf }.sortedWith(ShelfOrder)
    }

    override suspend fun getArticle(
        id: String,
        inTrash: Boolean,
    ): ArticleDetail {
        failIfRequested()
        val article = articles[id]?.takeIf { (it.shelf == Shelf.Trash) == inTrash } ?: throw notFound(id)
        return ArticleDetail(article, bodies[id])
    }

    override suspend fun setArchived(
        id: String,
        expectedRevision: Long,
        archived: Boolean,
    ): Article {
        val current = beginWrite(id, expectedRevision)
        if (current.trashedAt != null) throw notFound(id)
        if ((current.archivedAt != null) == archived) return current
        return save(current.copy(revision = current.revision + 1, archivedAt = if (archived) tick() else null))
    }

    override suspend fun moveToTrash(
        id: String,
        expectedRevision: Long,
    ): Article {
        val current = beginWrite(id, expectedRevision)
        if (current.trashedAt != null) throw notFound(id)
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
        val current = articles[id] ?: throw notFound(id)
        if (current.revision != expectedRevision) throw IOException("Stale revision for $id")
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

    private fun notFound(id: String) = IOException("No article $id")
}
