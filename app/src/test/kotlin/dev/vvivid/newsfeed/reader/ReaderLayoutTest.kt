package dev.vvivid.newsfeed.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLayoutTest {
    @Test
    fun `a narrow table stretches to fill the width in proportion`() {
        assertArrayEquals(intArrayOf(100, 300), columnWidths(intArrayOf(50, 150), intArrayOf(50, 100), available = 400))
    }

    @Test
    fun `a wide table narrows its wrapping columns to fit`() {
        // 100 px of room above the minimums, shared 1:3 by what each column can give up.
        assertArrayEquals(intArrayOf(125, 175), columnWidths(intArrayOf(200, 400), intArrayOf(100, 100), available = 300))
    }

    @Test
    fun `a table whose minimums do not fit keeps them and scrolls`() {
        assertArrayEquals(intArrayOf(150, 200), columnWidths(intArrayOf(240, 240), intArrayOf(150, 200), available = 300))
    }

    @Test
    fun `rounding leftovers go to the last column`() {
        assertEquals(301, columnWidths(intArrayOf(10, 10, 10), intArrayOf(10, 10, 10), available = 301).sum())
    }

    @Test
    fun `a first heading that repeats the title is dropped`() {
        val intro = BodyBlock.Paragraph(BodyText("Intro"))
        val body = listOf(BodyBlock.Heading(1, BodyText("Designing a Service")), intro)

        assertEquals(listOf(intro), readerBody(body, title = "Designing a service "))
        assertEquals(body, readerBody(body, title = "Something else"))
    }

    @Test
    fun `sections list headings down to level three with their list items`() {
        val body =
            listOf(
                BodyBlock.Paragraph(BodyText("Intro")),
                BodyBlock.Heading(2, BodyText("Step 1")),
                BodyBlock.Heading(3, BodyText("Detail")),
                BodyBlock.Heading(4, BodyText("Too fine")),
            )

        assertEquals(
            listOf(Section("Step 1", level = 2, itemIndex = 3), Section("Detail", level = 3, itemIndex = 4)),
            sections(body),
        )
    }
}
