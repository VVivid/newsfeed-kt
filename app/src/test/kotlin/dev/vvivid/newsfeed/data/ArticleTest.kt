package dev.vvivid.newsfeed.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleTest {
    @Test
    fun `the shelf follows the archive and Trash timestamps, Trash first`() {
        assertEquals(Shelf.Active, testArticle("a").shelf)
        assertEquals(Shelf.Archive, testArticle("b", archivedHoursAgo = 1).shelf)
        assertEquals(Shelf.Trash, testArticle("c", trashedHoursAgo = 1).shelf)
        // Trash keeps the archive state, so an archived article in Trash is still in Trash.
        assertEquals(Shelf.Trash, testArticle("d", archivedHoursAgo = 2, trashedHoursAgo = 1).shelf)
    }

    @Test
    fun `an article is shelved when it was saved, archived or trashed`() {
        val saved = testArticle("a", savedHoursAgo = 30)
        val archived = testArticle("b", savedHoursAgo = 30, archivedHoursAgo = 20)
        val trashed = testArticle("c", savedHoursAgo = 30, archivedHoursAgo = 20, trashedHoursAgo = 10)

        assertEquals(saved.createdAt, saved.shelvedAt)
        assertEquals(archived.archivedAt, archived.shelvedAt)
        assertEquals(trashed.trashedAt, trashed.shelvedAt)
    }

    @Test
    fun `shelf order is newest first, then by descending ID`() {
        val older = testArticle("z", savedHoursAgo = 5)
        val newer = testArticle("a", savedHoursAgo = 1)
        val sameTimeLowId = testArticle("m", savedHoursAgo = 3)
        val sameTimeHighId = testArticle("n", savedHoursAgo = 3)

        val sorted = listOf(older, sameTimeLowId, newer, sameTimeHighId).sortedWith(ShelfOrder)

        assertEquals(listOf("a", "n", "m", "z"), sorted.map { it.id })
    }

    @Test
    fun `the source is the host name without www`() {
        assertEquals("example.com", sourceHostOf("https://www.example.com/articles/1"))
        assertEquals("news.example.org", sourceHostOf("https://news.example.org:8443/a?b=c#d"))
        assertEquals("example.net", sourceHostOf("https://example.net"))
        assertEquals("www2.example.com", sourceHostOf("https://www2.example.com/"))
    }

    @Test
    fun `an address without a host is shown whole`() {
        assertEquals("not a url", sourceHostOf("not a url"))
        assertEquals("https:///path", sourceHostOf("https:///path"))
    }
}
