package dev.vvivid.newsfeed.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * An article as the server's JSON describes it, in lists (`GET /api/v1/articles`), in full
 * (`GET /api/v1/articles/{id}`) and after a state change. Only the fields the app uses are declared;
 * timestamps are Unix milliseconds, and the server leaves out empty and zero values.
 */
@Serializable
internal class ArticleJson(
    val id: String,
    /** Left out for an article the agent wrote itself without a source. */
    val url: String? = null,
    val title: String,
    val summary: String,
    val revision: Long,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("archived_at") val archivedAt: Long? = null,
    @SerialName("trashed_at") val trashedAt: Long? = null,
    @SerialName("thumbnail_status") val thumbnailStatus: String? = null,
    @SerialName("thumbnail_hash") val thumbnailHash: String? = null,
    @SerialName("content_markdown") val contentMarkdown: String? = null,
    /** Lowercase slugs in the server's order; left out when the article has none. */
    val tags: List<String>? = null,
) {
    fun toArticle(): Article =
        Article(
            id = id,
            url = url?.ifEmpty { null },
            title = title,
            summary = summary,
            revision = revision,
            createdAt = Instant.ofEpochMilli(createdAt),
            archivedAt = timestampOrNull(archivedAt),
            trashedAt = timestampOrNull(trashedAt),
            thumbnailKey = thumbnailKey(),
            tags = tags.orEmpty(),
        )

    fun toDetail(): ArticleDetail = ArticleDetail(toArticle(), contentMarkdown?.ifEmpty { null })

    /**
     * Saved thumbnails (`stored`) and older proxied ones (`legacy`) can be loaded; other statuses
     * mean there is no image. A changed image changes its hash, or at least the revision.
     */
    private fun thumbnailKey(): String? =
        when (thumbnailStatus) {
            "stored" -> thumbnailHash?.ifEmpty { null } ?: "revision-$revision"
            "legacy" -> "revision-$revision"
            else -> null
        }
}

/** Zero means "never" on the server, which normally leaves such a timestamp out altogether. */
private fun timestampOrNull(millis: Long?): Instant? = millis?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)

/** One page of a list (`GET /api/v1/articles`). */
@Serializable
internal class ArticleListJson(
    /** Required, but null for an empty page: that is how the server encodes an empty list. */
    val articles: List<ArticleJson>?,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/**
 * The body of a state change (`POST /api/v1/articles/{id}/archive` and the others). The server
 * rejects any other field.
 */
@Serializable
internal class ArticleStateChangeJson(
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

/** The tags on one shelf (`GET /api/v1/tags`), most used first. */
@Serializable
internal class TagListJson(
    val tags: List<TagCountJson>? = null,
)

@Serializable
internal class TagCountJson(
    val tag: String,
    val count: Int,
)
