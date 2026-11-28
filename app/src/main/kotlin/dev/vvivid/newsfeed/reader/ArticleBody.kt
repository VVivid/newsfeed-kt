package dev.vvivid.newsfeed.reader

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import java.net.URI
import java.net.URISyntaxException

/** One block of an article's saved body, ready to display. */
sealed interface BodyBlock {
    /** A heading; [level] is 1 for the largest and at most 6. */
    data class Heading(
        val level: Int,
        val text: BodyText,
    ) : BodyBlock

    data class Paragraph(
        val text: BodyText,
    ) : BodyBlock

    data class Quote(
        val blocks: List<BodyBlock>,
    ) : BodyBlock

    /** A list whose items are blocks themselves. Numbered from [firstNumber], or bulleted if null. */
    data class ListBlock(
        val firstNumber: Int?,
        val items: List<List<BodyBlock>>,
    ) : BodyBlock

    /** A ```mermaid block: a diagram that Mermaid draws from [source]. */
    data class Diagram(
        val source: String,
    ) : BodyBlock

    /**
     * Preformatted text, shown in a monospace font without wrapping. [language] is the first word
     * of a fenced block's info string, lowercased, such as "go"; null for none or an indented block.
     */
    data class CodeBlock(
        val code: String,
        val language: String? = null,
    ) : BodyBlock

    /**
     * A table: a [header] row and body [rows]. Every row has one cell per column, and [alignments]
     * has one entry per column.
     */
    data class Table(
        val alignments: List<CellAlignment>,
        val header: List<BodyText>,
        val rows: List<List<BodyText>>,
    ) : BodyBlock

    /** A horizontal rule between sections. */
    data object Rule : BodyBlock
}

/** How a table column lines up its cells, as the Markdown's delimiter row says. */
enum class CellAlignment { Start, Center, End }

/** A block's text, with the [ranges] that are styled or linked. */
data class BodyText(
    val text: String,
    val ranges: List<StyledRange> = emptyList(),
)

/** [style] applies to `text[start until end]` of a [BodyText]. */
data class StyledRange(
    val style: InlineStyle,
    val start: Int,
    val end: Int,
)

sealed interface InlineStyle {
    data object Emphasis : InlineStyle

    data object Strong : InlineStyle

    data object Code : InlineStyle

    /** A link to [url], an absolute http or https address (see [resolveLinkUrl]). */
    data class Link(
        val url: String,
    ) : InlineStyle
}

/**
 * Turns an article's saved Markdown into blocks to display, following CommonMark.
 *
 * The Markdown is untrusted, so only plain text and styling come out of it. Raw HTML is dropped, as
 * in the server's web reader: an HTML block disappears whole, while inline tags disappear and the
 * text between them stays. A link keeps its destination only if [resolveLinkUrl] accepts it against
 * [sourceUrl]. Images show their alt text, because article images load only through the server.
 *
 * Parsing a long article takes a while: call this off the main thread.
 */
fun parseArticleBody(
    markdown: String,
    sourceUrl: String,
): List<BodyBlock> = BodyBuilder(sourceUrl).blocks(MarkdownParser.parse(markdown))

/**
 * Nested quotes and lists beyond this depth are read as text. The limit keeps hostile input from
 * nesting deep enough to exhaust the stack when the body is converted and laid out.
 */
private const val MAX_OPEN_BLOCKS = 16

/**
 * Links and emphasis nested deeper than this are read as text, for the same reason: converting
 * inline content recurses once per level. Set explicitly because the library's default allows more.
 */
private const val MAX_INLINE_NESTING = 16

/** CommonMark parsers are immutable and thread-safe, so one serves every article. */
private val MarkdownParser: Parser =
    Parser
        .builder()
        .maxOpenBlockParsers(MAX_OPEN_BLOCKS)
        .maxInlineNesting(MAX_INLINE_NESTING)
        // Agents write tables in the GitHub style, as the server's web reader shows them.
        .extensions(listOf(TablesExtension.create()))
        .build()

/** Converts a parsed CommonMark document into [BodyBlock]s. */
private class BodyBuilder(
    private val sourceUrl: String,
) {
    fun blocks(parent: Node): List<BodyBlock> = parent.children().mapNotNull(::block).toList()

    private fun block(node: Node): BodyBlock? =
        when (node) {
            is Heading -> text(node)?.let { BodyBlock.Heading(node.level, it) }

            is Paragraph -> text(node)?.let { BodyBlock.Paragraph(it) }

            is BlockQuote -> blocks(node).ifEmpty { null }?.let { BodyBlock.Quote(it) }

            is BulletList -> listBlock(node, firstNumber = null)

            is OrderedList -> listBlock(node, firstNumber = node.markerStartNumber ?: 1)

            is FencedCodeBlock -> if (node.isMermaid()) diagram(node.literal) else codeBlock(node.literal, node.language())

            is IndentedCodeBlock -> codeBlock(node.literal)

            is ThematicBreak -> BodyBlock.Rule

            is TableBlock -> table(node)

            // An HTML block is dropped whole, text included. Link reference definitions only feed links.
            else -> null
        }

    /** Empty items stay, as in CommonMark, so that the numbers of the items after them stay right. */
    private fun listBlock(
        list: Node,
        firstNumber: Int?,
    ): BodyBlock = BodyBlock.ListBlock(firstNumber, items = list.children().map(::blocks).toList())

    private fun diagram(literal: String?): BodyBlock? = literal?.trimEnd('\n')?.takeIf { it.isNotBlank() }?.let { BodyBlock.Diagram(it) }

    private fun codeBlock(
        literal: String?,
        language: String? = null,
    ): BodyBlock? = literal?.trimEnd('\n')?.takeIf { it.isNotBlank() }?.let { BodyBlock.CodeBlock(it, language) }

    /** The inline content of [block], or null if nothing visible is left. */
    private fun text(block: Node): BodyText? = InlineTextBuilder(sourceUrl).apply { appendChildren(block) }.build()

    /** A table whose rows all have as many cells as its header, so its columns line up. */
    private fun table(table: TableBlock): BodyBlock? {
        val rows =
            table
                .children()
                .flatMap { section -> section.children().filterIsInstance<TableRow>().map { row -> section to row } }
                .toList()
        val headerRow = rows.firstOrNull { (section, _) -> section is TableHead }?.second ?: return null
        val headerCells = headerRow.children().filterIsInstance<TableCell>().toList()
        val columns = headerCells.size
        if (columns == 0) return null
        val alignments =
            headerCells.map {
                when (it.alignment) {
                    TableCell.Alignment.CENTER -> CellAlignment.Center
                    TableCell.Alignment.RIGHT -> CellAlignment.End
                    else -> CellAlignment.Start
                }
            }
        val body = rows.filter { (section, _) -> section !is TableHead }.map { (_, row) -> cells(row, columns) }
        return BodyBlock.Table(alignments, cells(headerRow, columns), body)
    }

    /** [row]'s first [columns] cells, padded with empty ones if it has fewer. */
    private fun cells(
        row: TableRow,
        columns: Int,
    ): List<BodyText> {
        val cells =
            row
                .children()
                .filterIsInstance<TableCell>()
                .take(columns)
                .map { InlineTextBuilder(sourceUrl).apply { appendChildren(it) }.build() ?: BodyText("") }
                .toList()
        return cells + List(columns - cells.size) { BodyText("") }
    }
}

/** Flattens a block's inline nodes into one string and the ranges styled within it. */
private class InlineTextBuilder(
    private val sourceUrl: String,
) {
    private val text = StringBuilder()
    private val ranges = mutableListOf<StyledRange>()

    fun build(): BodyText? = if (text.isBlank()) null else BodyText(text.toString(), ranges.toList())

    fun appendChildren(parent: Node) {
        for (child in parent.children()) append(child)
    }

    private fun append(node: Node) {
        when (node) {
            is Text -> {
                text.append(node.literal)
            }

            is SoftLineBreak -> {
                text.append(' ')
            }

            is HardLineBreak -> {
                text.append('\n')
            }

            is Code -> {
                styled(InlineStyle.Code) { text.append(node.literal) }
            }

            is Emphasis -> {
                styled(InlineStyle.Emphasis) { appendChildren(node) }
            }

            is StrongEmphasis -> {
                styled(InlineStyle.Strong) { appendChildren(node) }
            }

            is Link -> {
                val url = resolveLinkUrl(node.destination, sourceUrl)
                if (url == null) appendChildren(node) else styled(InlineStyle.Link(url)) { appendChildren(node) }
            }

            // The children of an image are its alt text.
            is Image -> {
                styled(InlineStyle.Emphasis) { appendChildren(node) }
            }

            // Raw inline HTML is dropped.
            else -> {
                Unit
            }
        }
    }

    private inline fun styled(
        style: InlineStyle,
        appendContent: () -> Unit,
    ) {
        val start = text.length
        appendContent()
        if (text.length > start) ranges += StyledRange(style, start, text.length)
    }
}

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

/**
 * The address a link in an article opens, or null if it may not be opened: the link then shows as
 * plain text.
 *
 * As in the server's web reader, relative, protocol-relative and fragment links resolve against
 * the article's [sourceUrl], because they belong to the source site. Only http and https results
 * with a host are accepted; `javascript:`, `data:`, `mailto:` and every other scheme are not.
 */
internal fun resolveLinkUrl(
    destination: String?,
    sourceUrl: String,
): String? {
    val target = destination?.trim().orEmpty()
    // java.net.URI resolves an empty reference to the parent directory, not to the page itself.
    if (target.isEmpty()) return null
    val resolved =
        try {
            sourceBaseUri(sourceUrl).resolve(target)
        } catch (e: URISyntaxException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
    val scheme = resolved.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (resolved.host.isNullOrEmpty()) return null
    return resolved.toString()
}

/**
 * [sourceUrl] as a base for resolving links. An address without a path, such as
 * `https://example.com`, gets the root path, as in a browser: java.net.URI would otherwise resolve
 * `about` against it to `https://example.comabout`.
 */
private fun sourceBaseUri(sourceUrl: String): URI {
    val source = URI(sourceUrl)
    if (source.isOpaque || source.rawAuthority == null || !source.rawPath.isNullOrEmpty()) return source
    // Rebuilt from the raw (still encoded) parts, so nothing is encoded twice.
    val query = source.rawQuery?.let { "?$it" }.orEmpty()
    val fragment = source.rawFragment?.let { "#$it" }.orEmpty()
    return URI("${source.scheme}://${source.rawAuthority}/$query$fragment")
}

/** A fenced block whose info string names Mermaid, such as ```mermaid. */
private fun FencedCodeBlock.isMermaid(): Boolean = info?.trim()?.substringBefore(' ')?.equals("mermaid", ignoreCase = true) == true

/** The language a fenced block names, such as "go" for ```go; null when it names none. */
private fun FencedCodeBlock.language(): String? =
    info
        ?.trim()
        ?.substringBefore(' ')
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_LANGUAGE_LENGTH }

private const val MAX_LANGUAGE_LENGTH = 20
