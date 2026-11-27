package dev.vvivid.newsfeed.data

import java.time.Duration
import java.time.Instant

/** The moment tests treat as now. */
val TestNow: Instant = Instant.parse("2026-11-25T12:00:00Z")

/** A synthetic article on a reserved example domain, saved [savedHoursAgo] before [TestNow]. */
fun testArticle(
    id: String,
    savedHoursAgo: Long = 1,
    archivedHoursAgo: Long? = null,
    trashedHoursAgo: Long? = null,
    revision: Long = 1,
    tags: List<String> = emptyList(),
    url: String? = "https://news.example.com/articles/$id",
) = Article(
    id = id,
    url = url,
    title = "Title of $id",
    summary = "Summary of $id.",
    revision = revision,
    createdAt = TestNow - Duration.ofHours(savedHoursAgo),
    archivedAt = archivedHoursAgo?.let { TestNow - Duration.ofHours(it) },
    trashedAt = trashedHoursAgo?.let { TestNow - Duration.ofHours(it) },
    thumbnailKey = null,
    tags = tags,
)
