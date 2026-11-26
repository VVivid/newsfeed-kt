package dev.vvivid.newsfeed.ui.preview

import dev.vvivid.newsfeed.data.Article
import dev.vvivid.newsfeed.reader.BodyBlock
import dev.vvivid.newsfeed.reader.parseArticleBody
import java.time.Duration
import java.time.Instant

/**
 * Marks sample content that exists only for `@Preview` functions. Using it is a compile error
 * without an explicit opt-in, and only previews may opt in: the running app never shows sample
 * data.
 */
@RequiresOptIn(
    message = "Sample data for @Preview functions only. The running app never shows sample data.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class PreviewSampleData

/** Synthetic articles on reserved example domains, for previews. */
@PreviewSampleData
internal object PreviewSamples {
    /** The moment previews treat as now. */
    val now: Instant = Instant.parse("2026-11-25T12:00:00Z")

    val serverOrigin = "https://news.example.com"

    val signInHost = "auth.example.com"

    val activeArticles: List<Article> =
        listOf(
            article(
                id = "preview-1",
                url = "https://homelab.example.com/notes/quiet-home-server",
                title = "Keeping a home server quiet, cool and boring",
                summary = "Fan curves, sleep schedules and why fewer moving parts mean fewer weekend repairs.",
                savedAgo = Duration.ofHours(2),
            ),
            article(
                id = "preview-2",
                url = "https://android.example.org/posts/back-gesture",
                title = "Designing for a back gesture you can see coming",
                summary = "Showing the destination before the gesture ends changes how screens should animate.",
                savedAgo = Duration.ofHours(5),
            ),
            article(
                id = "preview-3",
                url = "https://data.example.com/guides/single-file-backups",
                title = "Backups for a single-file database",
                summary = "Write-ahead logs, online copies and testing a restore before you need one.",
                savedAgo = Duration.ofHours(27),
            ),
            article(
                id = "preview-4",
                url = "https://design.example.net/essays/calm-reading",
                title = "Calm interfaces for reading",
                summary = "Type, spacing and restraint matter more than motion when the words are the product.",
                savedAgo = Duration.ofDays(2),
            ),
        )

    val archivedArticles: List<Article> =
        listOf(
            article(
                id = "preview-5",
                url = "https://security.example.org/articles/native-app-sign-in",
                title = "How native apps should sign in",
                summary = "Authorization code with PKCE in the system browser, and why embedded web views are avoided.",
                savedAgo = Duration.ofDays(10),
                archivedAgo = Duration.ofDays(7),
            ),
            article(
                id = "preview-6",
                url = "https://infra.example.net/checklists/restore-drill",
                title = "A restore drill in five steps",
                summary = "Proving that a backup works by restoring it somewhere harmless.",
                savedAgo = Duration.ofDays(14),
                archivedAgo = Duration.ofDays(13),
            ),
        )

    val trashedArticles: List<Article> =
        listOf(
            article(
                id = "preview-7",
                url = "https://news.example.com/lists/ten-tools",
                title = "Ten tools you need this week",
                summary = "A roundup that was not worth keeping.",
                savedAgo = Duration.ofDays(20),
                trashedAgo = Duration.ofDays(16),
            ),
        )

    /** A saved body that uses every kind of block the reader shows. */
    val articleBody: List<BodyBlock> by lazy {
        parseArticleBody(
            markdown =
                """
                The full source article appears here from its saved Markdown, in a serif sized for long reading.

                ## Why it stays quiet

                Short paragraphs keep a comfortable line length on phones. Links such as
                [the project notes](https://homelab.example.com/notes) stay part of the text, and *emphasis*,
                **strong words** and `inline code` keep their meaning.

                > A quotation from the source looks like this.

                1. Headings, lists and quotations follow the newsprint style.
                2. Raw HTML from the source never appears.

                ```
                fan-curve --profile quiet
                ```
                """.trimIndent(),
            sourceUrl = activeArticles.first().url,
        )
    }

    private fun article(
        id: String,
        url: String,
        title: String,
        summary: String,
        savedAgo: Duration,
        archivedAgo: Duration? = null,
        trashedAgo: Duration? = null,
    ) = Article(
        id = id,
        url = url,
        title = title,
        summary = summary,
        revision = 1,
        createdAt = now - savedAgo,
        archivedAt = archivedAgo?.let { now - it },
        trashedAt = trashedAgo?.let { now - it },
        thumbnailKey = null,
    )
}
