package dev.vvivid.newsfeed.ui.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackCoverIndexTest {
    @Test
    fun `fnv1a32 matches the published FNV-1a test vectors`() {
        assertEquals(0x811C9DC5u, fnv1a32(ByteArray(0)))
        assertEquals(0xE40C292Cu, fnv1a32("a".encodeToByteArray()))
        assertEquals(0xBF9CF968u, fnv1a32("foobar".encodeToByteArray()))
    }

    @Test
    fun `fixed URLs keep their covers`() {
        // Computed independently of this code. If one changes, existing articles change covers.
        assertEquals(16, fallbackCoverIndex("https://example.com/"))
        assertEquals(15, fallbackCoverIndex("https://example.com/articles/1"))
        assertEquals(6, fallbackCoverIndex("https://example.org/news/2026/11/25/story"))
        assertEquals(11, fallbackCoverIndex("https://example.net/a?b=c#d"))
    }

    @Test
    fun `non-ASCII characters are hashed as UTF-8`() {
        assertEquals(18, fallbackCoverIndex("https://example.com/café"))
    }

    @Test
    fun `indices stay within the 20 patterns and reach all of them`() {
        assertEquals(20, CoverPattern.entries.size)

        val indices = (0 until 100).map { fallbackCoverIndex("https://example.com/articles/$it") }

        assertTrue(indices.all { it in 0 until 20 })
        assertEquals((0 until 20).toSet(), indices.toSet())
    }
}
