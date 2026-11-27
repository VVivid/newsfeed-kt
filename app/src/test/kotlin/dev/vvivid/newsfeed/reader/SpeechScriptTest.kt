package dev.vvivid.newsfeed.reader

import dev.vvivid.newsfeed.audio.HEADER_BLOCK
import dev.vvivid.newsfeed.audio.SpeechSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechScriptTest {
    private fun parts(markdown: String) = speechParts("Title", "A summary", parseArticleBody(markdown, ""))

    @Test
    fun `each block is read in order, with notes for code and diagrams`() {
        val parts =
            parts(
                """
                ## Step one

                A paragraph.

                - first item
                - second item

                | Approach | Pros |
                |---|---|
                | Mutex | Simple |

                ```go
                x := 1
                ```

                ```mermaid
                flowchart LR
                  A --> B
                ```

                ---

                > Quoted words
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                SpeechSegment(HEADER_BLOCK, "Title. A summary."),
                SpeechSegment(0, "Step one"),
                SpeechSegment(1, "A paragraph."),
                SpeechSegment(2, "first item."),
                SpeechSegment(2, "second item."),
                SpeechSegment(3, "Mutex. Pros: Simple."),
                SpeechSegment(4, "Code example."),
                SpeechSegment(5, "Diagram."),
                SpeechSegment(7, "Quoted words"),
            ),
            parts.map { it.segment },
        )
    }

    @Test
    fun `headings and paragraphs keep where their text starts, so the sentence can be followed`() {
        val parts = parts("Plain words.")

        assertEquals(SpeechPart(SpeechSegment(0, "Plain words."), blockTextStart = 0), parts[1])
        assertEquals(null, parts[0].blockTextStart)
    }

    @Test
    fun `a long paragraph is split at sentence ends, keeping each piece's place`() {
        val sentence = "This sentence is about forty characters. "
        val text = sentence.repeat(200).trim()

        val parts = parts(text).drop(1)

        assertTrue(parts.size > 1)
        assertTrue(
            parts.all {
                it.segment.text
                    .toByteArray()
                    .size <= 4000
            },
        )
        assertEquals(text, parts.joinToString(" ") { it.segment.text })
        for (part in parts) {
            val start = part.blockTextStart!!
            assertEquals(part.segment.text, text.substring(start, start + part.segment.text.length))
        }
    }

    @Test
    fun `the cost and length follow the characters`() {
        val parts = parts("Twelve chars")

        assertEquals("Title. A summary.".length + "Twelve chars".length, speechCharacters(parts))
        assertEquals(1, speechMinutes(10))
        assertEquals(3, speechMinutes(1801))
    }

    @Test
    fun `the sentence around a character is found without its spaces`() {
        val text = "First one. Second one here. Third."

        assertEquals(11..26, sentenceAt(text, 15))
        assertEquals("Second one here.", text.substring(11, 27))
        assertEquals(0..9, sentenceAt(text, 0))
    }
}
