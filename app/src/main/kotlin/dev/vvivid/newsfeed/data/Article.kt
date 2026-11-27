package dev.vvivid.newsfeed.data

import java.time.Instant

/** The three lists of the user's newsfeed. The server keeps every article on exactly one. */
enum class Shelf {
    /** The feed of saved articles, newest saved first. */
    Active,

    /** Archived articles, newest archived first. */
    Archive,

    /** Recoverable Trash, newest trashed first. Trash keeps an article's content and archive state. */
    Trash,
}

/**
 * An article as AgentNewsfeed lists return it (`GET /api/v1/articles`): everything except the
 * saved body. Names follow the server's JSON fields, whose timestamps are Unix milliseconds.
 *
 * Titles, summaries and URLs come from agents and source sites: show them as plain text only.
 */
data class Article(
    val id: String,
    /**
     * The canonical HTTPS address of the original article, or null for an article the agent wrote
     * itself without a source (tagged [GENERATED_TAG]).
     */
    val url: String?,
    val title: String,
    /** A short introduction from the source (`summary`). */
    val summary: String,
    /** Changes with every edit and move. Writes send the revision they expect to replace. */
    val revision: Long,
    /** When the article was saved (`created_at`). */
    val createdAt: Instant,
    /** When it was archived (`archived_at`). Kept while the article is in Trash. */
    val archivedAt: Instant?,
    /** When it was moved to Trash (`trashed_at`). */
    val trashedAt: Instant?,
    /**
     * Identifies the server's thumbnail for the article, or null when it has none and the app shows
     * a generated fallback cover. A new image gets a new key, so the key names cached images: the
     * image's hash (`thumbnail_hash`) for a saved thumbnail, otherwise the article's revision.
     */
    val thumbnailKey: String?,
    /** The agent's tags, lowercase slugs such as `explore` or `system-design`. */
    val tags: List<String> = emptyList(),
) {
    /** The site the article comes from, as the server's web reader names it; null without a URL. */
    val sourceHost: String? = url?.let(::sourceHostOf)

    /** The agent wrote this article itself instead of saving one from a source. */
    val isGenerated: Boolean
        get() = GENERATED_TAG in tags

    /** The shelf the article is on. An article in Trash keeps [archivedAt], so Trash comes first. */
    val shelf: Shelf
        get() =
            when {
                trashedAt != null -> Shelf.Trash
                archivedAt != null -> Shelf.Archive
                else -> Shelf.Active
            }

    /** When the article arrived on its [shelf]: when it was saved, archived or trashed. */
    val shelvedAt: Instant
        get() = trashedAt ?: archivedAt ?: createdAt
}

/**
 * The server's order within each shelf: newest [Article.shelvedAt] first, ties broken by
 * descending ID (`created_at`, `archived_at` or `trashed_at` DESC, then `id` DESC).
 */
val ShelfOrder: Comparator<Article> =
    compareByDescending<Article> { it.shelvedAt }.thenByDescending { it.id }

/** The tag agents put on articles they wrote themselves. */
const val GENERATED_TAG = "generated"

/** The tag agents put on articles they found on the internet. */
const val EXPLORE_TAG = "explore"

/**
 * The only tags the app shows and filters by. Articles keep their other tags on the server, but
 * the app does not show them.
 */
val SHOWN_TAGS = listOf(EXPLORE_TAG, GENERATED_TAG)

/** A tag in use on a shelf and how many of the shelf's articles have it. */
data class TagCount(
    val tag: String,
    val count: Int,
)

/** A whole article (`GET /api/v1/articles/{id}`): the listed fields plus the saved body. */
data class ArticleDetail(
    val article: Article,
    /**
     * The complete original article as Markdown (`content_markdown`), or null when the agent saved
     * only the summary. Untrusted: it must be rendered without raw HTML.
     */
    val contentMarkdown: String?,
)

/**
 * The host name in [url] without a leading `www.`, as the server's web reader shows sources. The
 * server only stores HTTPS URLs with a DNS host name; anything else falls back to the whole URL so
 * that a source is always shown.
 */
internal fun sourceHostOf(url: String): String {
    val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
    val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
    val host = authority.substringAfterLast('@').substringBefore(':')
    return host.removePrefix("www.").ifEmpty { url }
}
