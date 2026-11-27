package dev.vvivid.newsfeed.reader

import dev.vvivid.newsfeed.audio.HEADER_BLOCK
import dev.vvivid.newsfeed.audio.SpeechSegment
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil

/**
 * One part of the article read aloud, and where its text is in the reader.
 *
 * @property blockTextStart where [segment]'s text starts in its block's text, for a heading or
 *   paragraph read word for word; null when the spoken text is not a slice of the block's text
 *   (lists, tables, quotes, code, diagrams). The reader follows the current sentence only in
 *   blocks that have it, and highlights the whole block otherwise.
 */
data class SpeechPart(
    val segment: SpeechSegment,
    val blockTextStart: Int?,
)

/**
 * The parts to read aloud for an article with [title], [summary] and the reader's [body] (as
 * [readerBody] returns it, so block indices match the reader's list). The server makes one audio
 * file per part, in this order.
 *
 * Code and diagrams cannot be read aloud, so they get a short spoken note; rules are skipped.
 */
fun speechParts(
    title: String,
    summary: String,
    body: List<BodyBlock>,
): List<SpeechPart> =
    buildList {
        val header = listOf(title, summary).map(::spokenText).filter { it.isNotEmpty() }.joinToString(" ") { withStop(it) }
        if (header.isNotEmpty()) add(SpeechPart(SpeechSegment(HEADER_BLOCK, header), blockTextStart = null))
        body.forEachIndexed { index, block -> addAll(blockParts(index, block)) }
    }.flatMap(::splitLongPart)

/** The characters the voice service reads for [parts], which is what creating the audio costs. */
fun speechCharacters(parts: List<SpeechPart>): Int = parts.sumOf { it.segment.text.length }

/** Roughly how long [characters] take to read aloud at normal speed. */
fun speechMinutes(characters: Int): Int = ceil(characters / CHARACTERS_PER_MINUTE).toInt().coerceAtLeast(1)

/** Speech runs at about 150 words, some 900 characters, a minute. */
private const val CHARACTERS_PER_MINUTE = 900.0

/** The server accepts at most 4500 bytes per part; this leaves room. */
private const val MAX_PART_BYTES = 4000

private fun blockParts(
    index: Int,
    block: BodyBlock,
): List<SpeechPart> =
    when (block) {
        is BodyBlock.Heading -> {
            listOfNotNull(verbatimPart(index, block.text))
        }

        is BodyBlock.Paragraph -> {
            listOfNotNull(verbatimPart(index, block.text))
        }

        is BodyBlock.Quote -> {
            block.blocks.flatMap(::spokenTexts).map { part(index, it) }
        }

        is BodyBlock.ListBlock -> {
            block.items
                .map { item -> item.flatMap(::spokenTexts).joinToString(" ") { withStop(it) } }
                .filter { it.isNotEmpty() }
                .map { part(index, it) }
        }

        is BodyBlock.Table -> {
            block.rows.mapNotNull { row -> tableRowText(block.header, row) }.map { part(index, it) }
        }

        is BodyBlock.CodeBlock -> {
            listOf(part(index, "Code example."))
        }

        is BodyBlock.Diagram -> {
            listOf(part(index, "Diagram."))
        }

        BodyBlock.Rule -> {
            emptyList()
        }
    }

/** A heading or paragraph read word for word, keeping where its text starts in the block. */
private fun verbatimPart(
    index: Int,
    text: BodyText,
): SpeechPart? {
    // Hard line breaks become spaces; the length stays the same, so offsets still match.
    val raw = text.text.replace('\n', ' ')
    val start = raw.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return null
    return SpeechPart(SpeechSegment(index, raw.trim()), blockTextStart = start)
}

private fun part(
    index: Int,
    text: String,
): SpeechPart = SpeechPart(SpeechSegment(index, text), blockTextStart = null)

/** The texts of a block nested in a quote or list item, flattened. */
private fun spokenTexts(block: BodyBlock): List<String> =
    when (block) {
        is BodyBlock.Heading -> listOf(spokenText(block.text.text))
        is BodyBlock.Paragraph -> listOf(spokenText(block.text.text))
        is BodyBlock.Quote -> block.blocks.flatMap(::spokenTexts)
        is BodyBlock.ListBlock -> block.items.flatMap { item -> item.flatMap(::spokenTexts) }
        is BodyBlock.Table -> block.rows.mapNotNull { tableRowText(block.header, it) }
        is BodyBlock.CodeBlock -> listOf("Code example.")
        is BodyBlock.Diagram -> listOf("Diagram.")
        BodyBlock.Rule -> emptyList()
    }.filter { it.isNotEmpty() }

/** A table row read as "First cell. Column: value. Column: value." */
private fun tableRowText(
    header: List<BodyText>,
    row: List<BodyText>,
): String? {
    val cells = row.map { spokenText(it.text) }
    val first = cells.firstOrNull().orEmpty()
    val rest =
        cells.drop(1).mapIndexedNotNull { column, value ->
            if (value.isEmpty()) return@mapIndexedNotNull null
            val name = spokenText(header.getOrNull(column + 1)?.text.orEmpty())
            if (name.isEmpty()) withStop(value) else "$name: ${withStop(value)}"
        }
    val parts = listOfNotNull(first.takeIf { it.isNotEmpty() }?.let(::withStop)) + rest
    return parts.joinToString(" ").ifEmpty { null }
}

private fun spokenText(text: String): String = text.replace(Whitespace, " ").trim()

private val Whitespace = Regex("\\s+")

/** [text] ending with punctuation, so the voice pauses before the next sentence. */
private fun withStop(text: String): String = if (text.last() in ".!?:;") text else "$text."

/**
 * Splits a part longer than the server accepts at sentence ends, keeping each piece's position in
 * its block. A single sentence that is still too long is split between words.
 */
private fun splitLongPart(part: SpeechPart): List<SpeechPart> {
    val text = part.segment.text
    if (text.toByteArray().size <= MAX_PART_BYTES) return listOf(part)
    val pieces = mutableListOf<IntRange>()
    var pieceStart = 0
    var pieceEnd = 0
    for (sentence in boundaries(text, BreakIterator.getSentenceInstance(Locale.ROOT))) {
        if (pieceEnd > pieceStart && text.substring(pieceStart, sentence.last + 1).toByteArray().size > MAX_PART_BYTES) {
            pieces += pieceStart until pieceEnd
            pieceStart = pieceEnd
        }
        pieceEnd = sentence.last + 1
        if (text.substring(pieceStart, pieceEnd).toByteArray().size > MAX_PART_BYTES) {
            pieces += splitBetweenWords(text, pieceStart until pieceEnd)
            pieceStart = pieceEnd
        }
    }
    if (pieceEnd > pieceStart) pieces += pieceStart until pieceEnd
    return pieces.mapNotNull { range ->
        val piece = text.substring(range.first, range.last + 1)
        val leading = piece.indexOfFirst { !it.isWhitespace() }
        if (leading < 0) return@mapNotNull null
        SpeechPart(
            SpeechSegment(part.segment.block, piece.trim()),
            blockTextStart = part.blockTextStart?.let { it + range.first + leading },
        )
    }
}

private fun splitBetweenWords(
    text: String,
    range: IntRange,
): List<IntRange> {
    val pieces = mutableListOf<IntRange>()
    var start = range.first
    var end = range.first
    for (word in boundaries(text.substring(range.first, range.last + 1), BreakIterator.getWordInstance(Locale.ROOT))) {
        val wordEnd = range.first + word.last + 1
        if (end > start && text.substring(start, wordEnd).toByteArray().size > MAX_PART_BYTES) {
            pieces += start until end
            start = end
        }
        end = wordEnd
    }
    if (end > start) pieces += start until end
    return pieces
}

private fun boundaries(
    text: String,
    iterator: BreakIterator,
): List<IntRange> {
    iterator.setText(text)
    val ranges = mutableListOf<IntRange>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        ranges += start until end
        start = end
        end = iterator.next()
    }
    return ranges
}

/**
 * The sentence of [text] that contains character [index] (a UTF-16 index), as a range of [text]
 * without its surrounding spaces.
 */
fun sentenceAt(
    text: String,
    index: Int,
): IntRange? {
    if (text.isEmpty()) return null
    val iterator = BreakIterator.getSentenceInstance(Locale.ROOT)
    iterator.setText(text)
    val at = index.coerceIn(0, text.length - 1)
    val end = iterator.following(at).let { if (it == BreakIterator.DONE) text.length else it }
    val start = iterator.previous().let { if (it == BreakIterator.DONE) 0 else it }
    val sentence = text.substring(start, end)
    val leading = sentence.indexOfFirst { !it.isWhitespace() }
    if (leading < 0) return null
    val trailing = sentence.length - sentence.trimEnd().length
    return (start + leading) until (end - trailing)
}
