package dev.vvivid.newsfeed.reader

import dev.vvivid.newsfeed.audio.HEADER_BLOCK
import dev.vvivid.newsfeed.audio.SpeechSegment
import dev.vvivid.newsfeed.audio.SyncPoint
import dev.vvivid.newsfeed.data.NarrationPart

/**
 * The parts to read aloud from the agent's [narration] script for an article with [title] and the
 * reader's [body]: the voice says the script's words, and sync points tie them to the text the
 * reader shows, so the highlight follows the voice. The summary is not read.
 *
 * The script pairs passages of the title and body, in order, with how to say them, and stands one
 * described part for each code block, diagram and table; the server checked it against the same
 * article. Passages are compared by their letters and digits, as the server does. Returns null
 * when there is no script or it doesn't line up with [body], so the text is read as written.
 */
internal fun narratedParts(
    title: String,
    body: List<BodyBlock>,
    narration: List<NarrationPart>,
): List<SpeechPart>? {
    if (narration.isEmpty()) return null
    val script = ScriptReader(narration)
    val parts = mutableListOf<SpeechPart>()
    parts += script.passage(HEADER_BLOCK, spokenText(title), verbatimStart = null) ?: return null
    body.forEachIndexed { index, block -> parts += script.block(index, block, verbatim = true) ?: return null }
    return parts.takeIf { script.finished }
}

/** Reads [narration] from the start, one passage or described block at a time. */
private class ScriptReader(
    private val narration: List<NarrationPart>,
) {
    private var next = 0

    val finished: Boolean get() = next == narration.size

    /**
     * The parts that read [block], at index [index] of the body. Only a top-level heading or
     * paragraph is [verbatim]: the reader then follows the current sentence inside it.
     */
    fun block(
        index: Int,
        block: BodyBlock,
        verbatim: Boolean,
    ): List<SpeechPart>? =
        when (block) {
            is BodyBlock.Heading -> verbatimPassage(index, block.text, verbatim)
            is BodyBlock.Paragraph -> verbatimPassage(index, block.text, verbatim)
            is BodyBlock.Quote -> nested(block.blocks.map { listOf(it) }, index)
            is BodyBlock.ListBlock -> nested(block.items, index)
            is BodyBlock.CodeBlock -> described(index, "code", "Code example.")
            is BodyBlock.Diagram -> described(index, "diagram", "Diagram.")
            is BodyBlock.Table -> described(index, "table", "Table.")
            BodyBlock.Rule -> emptyList()
        }

    private fun nested(
        groups: List<List<BodyBlock>>,
        index: Int,
    ): List<SpeechPart>? = groups.flatMap { group -> group.flatMap { block(index, it, verbatim = false) ?: return null } }

    private fun verbatimPassage(
        index: Int,
        text: BodyText,
        verbatim: Boolean,
    ): List<SpeechPart>? {
        // As in the plain reading: hard line breaks become spaces, which keeps offsets in the block.
        val raw = text.text.replace('\n', ' ')
        val start = raw.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return emptyList()
        return passage(index, raw.trim(), verbatimStart = start.takeIf { verbatim })
    }

    /** The one part the script has for a code block, diagram or table, shown as [shown]. */
    private fun described(
        index: Int,
        kind: String,
        shown: String,
    ): List<SpeechPart>? {
        val part = narration.getOrNull(next)?.takeIf { it.describe == kind } ?: return null
        next++
        return listOf(SpeechPart(SpeechSegment(index, shown, spoken = part.say), blockTextStart = null))
    }

    /**
     * Reads [shown] with the script's next text parts, which together must match it. Long passages
     * become several segments, split between parts. [verbatimStart] is where [shown] starts in its
     * block, for a passage whose sentences the reader follows.
     */
    fun passage(
        index: Int,
        shown: String,
        verbatimStart: Int?,
    ): List<SpeechPart>? {
        val key = MatchKey(shown)
        if (key.isEmpty) return emptyList()
        // Each part read, with the code point offset in shown of its first letter or digit.
        val placed = mutableListOf<Pair<NarrationPart, Int>>()
        var covered = 0
        while (covered < key.size) {
            val part = narration.getOrNull(next) ?: return null
            val partKey = MatchKey(part.text ?: return null)
            if (partKey.isEmpty || !key.matches(partKey, covered)) return null
            placed += part to key.codePointAt(covered)
            covered += partKey.size
            next++
        }
        return segments(index, shown, placed, verbatimStart)
    }

    /** Groups [placed] into segments the server accepts, each with its sync points. */
    private fun segments(
        index: Int,
        shown: String,
        placed: List<Pair<NarrationPart, Int>>,
        verbatimStart: Int?,
    ): List<SpeechPart>? {
        val groups = mutableListOf<List<Pair<NarrationPart, Int>>>()
        var group = mutableListOf<Pair<NarrationPart, Int>>()
        for (item in placed) {
            val longer = group + item
            if (group.isNotEmpty() && longer.joinToString(" ") { it.first.say }.toByteArray().size > MAX_PART_BYTES) {
                groups += group
                group = mutableListOf()
            }
            group += item
        }
        groups += group
        return groups.mapIndexed { g, members ->
            // The first group starts with shown; the others at their first part's first letter.
            val from = if (g == 0) 0 else members.first().second
            val to = groups.getOrNull(g + 1)?.first()?.second ?: shown.codePointCount(0, shown.length)
            val piece = shown.substring(shown.offsetByCodePoints(0, from), shown.offsetByCodePoints(0, to)).trimEnd()
            if (piece.toByteArray().size > MAX_SHOWN_BYTES) return null
            var spokenOffset = 0
            val sync = mutableListOf<SyncPoint>()
            members.forEachIndexed { i, (part, at) ->
                if (i > 0) sync += SyncPoint(at - from, spokenOffset)
                spokenOffset += part.say.codePointCount(0, part.say.length) + 1
            }
            val spoken = members.joinToString(" ") { it.first.say }
            SpeechPart(
                SpeechSegment(index, piece, spoken, sync),
                blockTextStart = verbatimStart?.let { it + shown.offsetByCodePoints(0, from) },
            )
        }
    }
}

/** The server accepts at most 4500 bytes of shown text per segment. */
private const val MAX_SHOWN_BYTES = 4500

/**
 * What narration text is compared by: the letters and digits of a text, lowercased, with the code
 * point offset of each. Punctuation, spacing and Markdown marks don't matter.
 */
private class MatchKey(
    text: String,
) {
    private val runes: IntArray
    private val offsets: IntArray

    init {
        val runes = mutableListOf<Int>()
        val offsets = mutableListOf<Int>()
        var codePoint = 0
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            if (Character.isLetter(c) || Character.isDigit(c)) {
                runes += Character.toLowerCase(c)
                offsets += codePoint
            }
            codePoint++
            i += Character.charCount(c)
        }
        this.runes = runes.toIntArray()
        this.offsets = offsets.toIntArray()
    }

    val size: Int get() = runes.size
    val isEmpty: Boolean get() = runes.isEmpty()

    fun codePointAt(index: Int): Int = offsets[index]

    /** Whether [other] continues this key at [from]. */
    fun matches(
        other: MatchKey,
        from: Int,
    ): Boolean = from + other.size <= size && other.runes.indices.all { runes[from + it] == other.runes[it] }
}
