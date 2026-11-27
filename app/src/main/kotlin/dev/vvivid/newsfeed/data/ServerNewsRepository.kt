package dev.vvivid.newsfeed.data

import dev.vvivid.newsfeed.auth.SessionEndedException
import dev.vvivid.newsfeed.net.ServerJson
import dev.vvivid.newsfeed.net.bodyText
import dev.vvivid.newsfeed.net.send
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.DeserializationStrategy
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The [NewsRepository] of a signed-in server: its article API under `/api/v1/articles`.
 *
 * [httpClient] carries the session's credentials (see `AuthInterceptor`); this class only shapes the
 * requests and reads the answers. The server answers in JSON; its errors become
 * [NewsServerException]s. A session that cannot be renewed fails requests with
 * [SessionEndedException], as does an HTTP 401 that reaches this class.
 *
 * @param newIdempotencyKey makes the key for a new state change; tests replace it.
 */
class ServerNewsRepository(
    private val httpClient: OkHttpClient,
    serverOrigin: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
) : NewsRepository {
    private val articlesUrl: HttpUrl =
        serverOrigin
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("api/v1/articles")
            .build()

    private val tagsUrl: HttpUrl =
        serverOrigin
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("api/v1/tags")
            .build()

    /**
     * The keys of state changes whose outcome is unknown: the request may have reached the server,
     * but no answer came back. Trying the same change again sends the same key, so the server
     * applies it once and answers the retry with the original result.
     */
    private val unconfirmedChangeKeys = ConcurrentHashMap<StateChange, String>()

    override suspend fun listArticles(
        shelf: Shelf,
        cursor: String?,
        tags: Set<String>,
    ): ArticlePage {
        val url =
            articlesUrl
                .newBuilder()
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addShelf(shelf)
                .apply {
                    // Repeated parameters: the server keeps articles having any of these tags.
                    tags.sorted().forEach { addQueryParameter("tag", it) }
                    if (cursor != null) addQueryParameter("cursor", cursor)
                }.build()
        val page = request(Request.Builder().url(url).get(), ArticleListJson.serializer())
        return ArticlePage(
            articles = page.articles.orEmpty().map { it.toArticle() },
            nextCursor = page.nextCursor?.ifEmpty { null },
        )
    }

    override suspend fun listTags(shelf: Shelf): List<TagCount> {
        val url = tagsUrl.newBuilder().addShelf(shelf).build()
        val list = request(Request.Builder().url(url).get(), TagListJson.serializer())
        return list.tags.orEmpty().map { TagCount(it.tag, it.count) }
    }

    /** Selects [shelf] as the list and tag routes expect: Active by default. */
    private fun HttpUrl.Builder.addShelf(shelf: Shelf): HttpUrl.Builder =
        apply {
            when (shelf) {
                Shelf.Active -> Unit
                Shelf.Archive -> addQueryParameter("archived", "true")
                Shelf.Trash -> addQueryParameter("trashed", "true")
            }
        }

    override suspend fun getArticle(
        id: String,
        inTrash: Boolean,
    ): ArticleDetail {
        val url =
            articlesUrl
                .newBuilder()
                .addPathSegment(id)
                .apply { if (inTrash) addQueryParameter("trashed", "true") }
                .build()
        return request(Request.Builder().url(url).get(), ArticleJson.serializer()).toDetail()
    }

    override suspend fun setArchived(
        id: String,
        expectedRevision: Long,
        archived: Boolean,
    ): Article = changeState(StateChange(id, expectedRevision, if (archived) StateChange.Kind.Archive else StateChange.Kind.Unarchive))

    override suspend fun moveToTrash(
        id: String,
        expectedRevision: Long,
    ): Article = changeState(StateChange(id, expectedRevision, StateChange.Kind.MoveToTrash))

    override suspend fun restoreFromTrash(
        id: String,
        expectedRevision: Long,
    ): Article = changeState(StateChange(id, expectedRevision, StateChange.Kind.RestoreFromTrash))

    /**
     * Sends [change] with its idempotency key. When the server refuses it (403, 404 or 409), it
     * applied nothing and kept no key, so the next attempt is a new request. Any other failure may
     * have happened after the server applied the change, so the key is kept for the next attempt.
     */
    private suspend fun changeState(change: StateChange): Article {
        val key = unconfirmedChangeKeys.getOrPut(change, newIdempotencyKey)
        val url =
            articlesUrl
                .newBuilder()
                .addPathSegment(change.articleId)
                .addPathSegment(change.kind.pathSegment)
                .build()
        val body = ServerJson.encodeToString(ArticleStateChangeJson.serializer(), ArticleStateChangeJson(change.expectedRevision, key))
        val article =
            try {
                request(Request.Builder().url(url).post(body.toRequestBody(JsonMediaType)), ArticleJson.serializer())
            } catch (e: NewsServerException) {
                if (e.isRefusal()) unconfirmedChangeKeys.remove(change)
                throw e
            }
        unconfirmedChangeKeys.remove(change)
        return article.toArticle()
    }

    /** An answer saying that the server did not apply the request. */
    private fun NewsServerException.isRefusal(): Boolean =
        this is ArticleConflictException || this is ArticleNotFoundException || this is PermissionDeniedException

    private suspend fun <T> request(
        builder: Request.Builder,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val request = builder.header("Accept", "application/json").build()
        return httpClient.send(request, ioDispatcher) { response ->
            response.throwIfError()
            val json = response.bodyText(MAX_RESPONSE_BYTES)
            try {
                ServerJson.decodeFromString(deserializer, json)
            } catch (e: IllegalArgumentException) {
                // SerializationException, for malformed JSON or a missing or mistyped field, is one.
                throw UnexpectedResponseException("The server's article JSON is not valid", e)
            }
        }
    }

    private fun Response.throwIfError() {
        when (code) {
            200 -> Unit
            401 -> throw SessionEndedException()
            403 -> throw PermissionDeniedException()
            404 -> throw ArticleNotFoundException()
            409 -> throw ArticleConflictException()
            else -> throw UnexpectedResponseException("The server answered HTTP $code")
        }
    }

    /** One state change the user asked for: what the server's idempotency key stands for. */
    private data class StateChange(
        val articleId: String,
        val expectedRevision: Long,
        val kind: Kind,
    ) {
        enum class Kind(
            val pathSegment: String,
        ) {
            Archive("archive"),

            /** The server calls this restore: Archive back to Active. */
            Unarchive("restore"),
            MoveToTrash("trash"),
            RestoreFromTrash("restore-trash"),
        }
    }

    private companion object {
        /** The most the server returns in one page. */
        const val PAGE_SIZE = 50

        /**
         * Above any valid answer: a saved body is at most 256 KiB of Markdown, which JSON escaping can
         * at most make six times longer.
         */
        const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024

        val JsonMediaType = "application/json".toMediaType()
    }
}
