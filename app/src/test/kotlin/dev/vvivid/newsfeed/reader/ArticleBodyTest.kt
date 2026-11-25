package dev.vvivid.newsfeed.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleBodyTest {
    private val source = "https://news.example.com/articles/one?page=2"

    private fun parse(markdown: String) = parseArticleBody(markdown, source)

    private fun paragraph(
        text: String,
        vararg ranges: StyledRange,
    ) = BodyBlock.Paragraph(BodyText(text, ranges.toList()))

    @Test
    fun `block structure follows the Markdown`() {
        val blocks =
            parse(
                """
                # Title

                First paragraph
                continues here.

                ## Section

                > Quoted

                - one
                - two

                3. three
                4. four

                ```
                code line
                ```

                ---
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                BodyBlock.Heading(1, BodyText("Title")),
                paragraph("First paragraph continues here."),
                BodyBlock.Heading(2, BodyText("Section")),
                BodyBlock.Quote(listOf(paragraph("Quoted"))),
                BodyBlock.ListBlock(firstNumber = null, items = listOf(listOf(paragraph("one")), listOf(paragraph("two")))),
                BodyBlock.ListBlock(firstNumber = 3, items = listOf(listOf(paragraph("three")), listOf(paragraph("four")))),
                BodyBlock.CodeBlock("code line"),
                BodyBlock.Rule,
            ),
            blocks,
        )
    }

    @Test
    fun `an empty list item keeps its place, so the numbers after it stay right`() {
        assertEquals(
            listOf(BodyBlock.ListBlock(firstNumber = 1, items = listOf(emptyList(), listOf(paragraph("two"))))),
            parse("1.\n2. two"),
        )
    }

    @Test
    fun `inline styles become ranges of the text`() {
        assertEquals(
            listOf(
                paragraph(
                    "a b c",
                    StyledRange(InlineStyle.Emphasis, 0, 1),
                    StyledRange(InlineStyle.Strong, 2, 3),
                    StyledRange(InlineStyle.Code, 4, 5),
                ),
            ),
            parse("*a* **b** `c`"),
        )
    }

    @Test
    fun `entities and escapes become plain characters`() {
        assertEquals(listOf(paragraph("Fish & chips *not emphasis*")), parse("Fish &amp; chips \\*not emphasis\\*"))
    }

    @Test
    fun `raw HTML is dropped and the text between inline tags stays`() {
        val blocks =
            parse(
                """
                <script>alert("block")</script>

                Text <b>bold</b><img src="https://tracker.example.net/pixel.gif"> here.
                """.trimIndent(),
            )

        assertEquals(listOf(paragraph("Text bold here.")), blocks)
    }

    @Test
    fun `links keep absolute http and https addresses`() {
        assertEquals(
            listOf(paragraph("read", StyledRange(InlineStyle.Link("https://example.org/a"), 0, 4))),
            parse("[read](https://example.org/a)"),
        )
        assertEquals(
            listOf(
                paragraph(
                    "https://example.org/b",
                    StyledRange(InlineStyle.Link("https://example.org/b"), 0, 21),
                ),
            ),
            parse("<https://example.org/b>"),
        )
    }

    @Test
    fun `links that are not http or https show as plain text`() {
        assertEquals(listOf(paragraph("run")), parse("[run](javascript:alert(1))"))
        assertEquals(listOf(paragraph("data")), parse("[data](data:text/html,hello)"))
        assertEquals(listOf(paragraph("mail")), parse("[mail](mailto:someone@example.com)"))
        assertEquals(listOf(paragraph("empty")), parse("[empty]()"))
    }

    @Test
    fun `images show their alt text`() {
        assertEquals(
            listOf(paragraph("A chart", StyledRange(InlineStyle.Emphasis, 0, 7))),
            parse("![A chart](https://images.example.com/chart.png)"),
        )
    }

    @Test
    fun `deeply nested input is flattened instead of overflowing the stack`() {
        val blocks = parse(">".repeat(10_000) + " deep")

        var depth = 0
        var block: BodyBlock? = blocks.single()
        while (block is BodyBlock.Quote) {
            depth++
            block = block.blocks.firstOrNull()
        }
        assertTrue("nesting depth $depth", depth in 1..16)
        assertTrue(block is BodyBlock.Paragraph)
    }

    @Test
    fun `relative, protocol-relative and fragment links resolve against the source`() {
        assertEquals("https://news.example.com/articles/two", resolveLinkUrl("two", source))
        assertEquals("https://news.example.com/about", resolveLinkUrl("/about", source))
        assertEquals("https://cdn.example.net/file", resolveLinkUrl("//cdn.example.net/file", source))
        assertEquals("https://news.example.com/articles/one?page=2#notes", resolveLinkUrl("#notes", source))
        assertEquals("http://example.org/plain", resolveLinkUrl(" http://example.org/plain ", source))
    }

    @Test
    fun `unusable link destinations resolve to nothing`() {
        assertNull(resolveLinkUrl(null, source))
        assertNull(resolveLinkUrl("", source))
        assertNull(resolveLinkUrl("javascript:alert(1)", source))
        assertNull(resolveLinkUrl("JavaScript:alert(1)", source))
        assertNull(resolveLinkUrl("data:text/html,hello", source))
        assertNull(resolveLinkUrl("ftp://example.org/file", source))
        assertNull(resolveLinkUrl("https:no-host", source))
        assertNull(resolveLinkUrl("https://news .example.org/", source))
    }
}
