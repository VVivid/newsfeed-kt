package dev.vvivid.newsfeed.reader

import dev.vvivid.newsfeed.audio.HEADER_BLOCK
import dev.vvivid.newsfeed.audio.SpeechSegment
import dev.vvivid.newsfeed.audio.SyncPoint
import dev.vvivid.newsfeed.data.NarrationPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NarrationScriptTest {
    private val markdown =
        """
        A customer owes **two** invoices of $100. Their [system](https://example.com) pays once.

        - The request never arrived.

        ```go
        mu.Lock()
        ```

        ---

        | Invoice | Amount |
        |---|---|
        | I1 | $100 |
        """.trimIndent()

    private fun text(
        text: String,
        say: String,
    ) = NarrationPart(text, null, say)

    private fun described(
        kind: String,
        say: String,
    ) = NarrationPart(null, kind, say)

    private val script =
        listOf(
            text("How a $100 payment", "How a hundred-dollar payment"),
            text("A customer owes two invoices of $100.", "A customer owes two invoices of a hundred dollars."),
            text("Their system pays once.", "Their system pays... once."),
            text("The request never arrived.", "One: the request never arrived."),
            described("code", "In the code, the service takes the lock."),
            described("table", "Invoice one is a hundred dollars."),
        )

    private fun parts(narration: List<NarrationPart>) =
        speechParts("How a $100 payment", "Not read aloud.", parseArticleBody(markdown, ""), narration)

    @Test
    fun `the voice says the script and sync points tie it to the shown text`() {
        val parts = parts(script)

        assertEquals(
            listOf(
                SpeechSegment(HEADER_BLOCK, "How a $100 payment", "How a hundred-dollar payment"),
                SpeechSegment(
                    0,
                    "A customer owes two invoices of $100. Their system pays once.",
                    "A customer owes two invoices of a hundred dollars. Their system pays... once.",
                    // "Their" is shown at code point 38 and spoken at 51.
                    listOf(SyncPoint(38, 51)),
                ),
                SpeechSegment(1, "The request never arrived.", "One: the request never arrived."),
                SpeechSegment(2, "Code example.", "In the code, the service takes the lock."),
                SpeechSegment(4, "Table.", "Invoice one is a hundred dollars."),
            ),
            parts.map { it.segment },
        )
        // The paragraph is followed sentence by sentence; the list item is highlighted whole.
        assertEquals(listOf(null, 0, null, null, null), parts.map { it.blockTextStart })
        // The paragraph's two parts are spoken with a space between them.
        assertEquals(script.sumOf { it.say.length } + 1, speechCharacters(parts))
    }

    @Test
    fun `a script that doesn't line up with the body is not used`() {
        for (broken in listOf(
            script.drop(1),
            script.dropLast(1),
            script + text("More", "More."),
            script.map { if (it.describe == "code") described("diagram", it.say) else it },
            script.map { if (it.text == "Their system pays once.") text("Their system pays twice.", it.say) else it },
        )) {
            val parts = parts(broken)
            assertEquals(SpeechSegment(HEADER_BLOCK, "How a $100 payment. Not read aloud."), parts.first().segment)
            assertNull(parts.firstNotNullOfOrNull { it.segment.spoken })
        }
    }

    @Test
    fun `a long passage is split between parts, each piece keeping its place and sync`() {
        val sentence = "Every word of this sentence is read exactly as written here. "
        val paragraph = sentence.repeat(130).trim()
        val narration = listOf(text("Title", "Title.")) + List(130) { text(sentence, sentence.trim()) }

        val parts = speechParts("Title", "", parseArticleBody(paragraph, ""), narration).drop(1)

        assertEquals(2, parts.size)
        assertEquals(paragraph, parts.joinToString(" ") { it.segment.text })
        assertEquals(0, parts[0].blockTextStart)
        assertEquals(paragraph.length - parts[1].segment.text.length, parts[1].blockTextStart)
        for (part in parts) {
            val segment = part.segment
            assert(segment.spoken!!.toByteArray().size <= MAX_PART_BYTES)
            // Every sentence after the first in a piece starts a sync point, at the same place in both texts.
            assertEquals(segment.text.split(". ").size - 1, segment.sync.size)
            for (point in segment.sync) {
                assertEquals(segment.text.drop(point.shown).take(10), segment.spoken.drop(point.spoken).take(10))
            }
        }
    }
}
