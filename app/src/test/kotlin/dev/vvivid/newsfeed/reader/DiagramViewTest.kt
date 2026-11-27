package dev.vvivid.newsfeed.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagramViewTest {
    @Test
    fun `a mermaid block becomes a diagram, other fenced blocks stay code`() {
        val blocks =
            parseArticleBody(
                "```mermaid\nflowchart LR\n  A --> B\n```\n\n```Mermaid title\nsequenceDiagram\n```\n\n```kotlin\nval x = 1\n```",
                sourceUrl = "",
            )

        assertEquals(
            listOf(
                BodyBlock.Diagram("flowchart LR\n  A --> B"),
                BodyBlock.Diagram("sequenceDiagram"),
                BodyBlock.CodeBlock("val x = 1"),
            ),
            blocks,
        )
    }

    @Test
    fun `the diagram page carries the source only as escaped text`() {
        val page = diagramPage("flowchart LR\n  A[\"<script>alert(1)</script>\"] --> B & C")

        assertFalse(page.contains("<script>alert"))
        assertTrue(page.contains("A[&quot;&lt;script&gt;alert(1)&lt;/script&gt;&quot;] --&gt; B &amp; C"))
        assertTrue(page.contains("<script src=\"mermaid.min.js\"></script>"))
    }
}
