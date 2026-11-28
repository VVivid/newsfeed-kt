package dev.vvivid.newsfeed.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.vvivid.newsfeed.ui.theme.ArticleBodyTextStyle

/** Space between the blocks inside a quote or a list item. */
private val NestedBlockSpacing = 8.dp

/**
 * One block of an article body. Links call [onLinkClick] with their address and open nothing by
 * themselves. [spokenSentence] marks the sentence the voice is reading in a heading or paragraph.
 * [onSpokenPlaced], given to the block the voice is reading, hears where the block is on screen
 * whenever it moves, so the reader can keep the voice's words in view.
 */
@Composable
internal fun BodyBlockView(
    block: BodyBlock,
    onLinkClick: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    spokenSentence: IntRange? = null,
    onSpokenPlaced: ((SpokenPlacement) -> Unit)? = null,
) {
    val textLayout = remember { TextLayoutRef() }
    val textModifier =
        if (onSpokenPlaced == null) {
            Modifier
        } else {
            // Last in the chain, so the coordinates are the text's own, inside any padding.
            Modifier.onGloballyPositioned { onSpokenPlaced(SpokenPlacement(it, textLayout.result)) }
        }
    val blockModifier =
        if (onSpokenPlaced == null) modifier else modifier.onGloballyPositioned { onSpokenPlaced(SpokenPlacement(it, null)) }
    when (block) {
        is BodyBlock.Heading -> {
            Text(
                text = rememberBodyText(block.text, onLinkClick, spokenSentence),
                // Extra room above a section heading sets the sections apart.
                modifier =
                    modifier
                        .padding(top = if (block.level <= 2) 18.dp else 8.dp)
                        .semantics { heading() }
                        .then(textModifier),
                color = MaterialTheme.colorScheme.tertiary,
                onTextLayout = { textLayout.result = it },
                style = headingStyle(block.level),
            )
        }

        is BodyBlock.Paragraph -> {
            Text(
                text = rememberBodyText(block.text, onLinkClick, spokenSentence),
                modifier = modifier.then(textModifier),
                onTextLayout = { textLayout.result = it },
                style = ArticleBodyTextStyle,
            )
        }

        is BodyBlock.Quote -> {
            QuoteView(block, onLinkClick, blockModifier)
        }

        is BodyBlock.ListBlock -> {
            ListBlockView(block, onLinkClick, blockModifier)
        }

        is BodyBlock.CodeBlock -> {
            CodeBlockView(block.code, blockModifier)
        }

        is BodyBlock.Diagram -> {
            DiagramView(block.source, blockModifier)
        }

        is BodyBlock.Table -> {
            TableView(block, onLinkClick, blockModifier)
        }

        BodyBlock.Rule -> {
            HorizontalDivider(modifier = blockModifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** The latest layout of a block's text, read when the block reports where it is. */
private class TextLayoutRef {
    var result: TextLayoutResult? = null
}

/**
 * Where a spoken block is: its [coordinates] on screen and, for a heading or paragraph, the
 * [text] layout that places each sentence inside them.
 */
internal class SpokenPlacement(
    val coordinates: LayoutCoordinates,
    val text: TextLayoutResult?,
) {
    /**
     * The top and bottom, in root coordinates, of [sentence]'s lines; of the whole block without a
     * sentence or text layout. Null once the block has left the screen.
     */
    fun boundsInRoot(sentence: IntRange?): ClosedFloatingPointRange<Float>? {
        if (!coordinates.isAttached) return null
        val top = coordinates.positionInRoot().y
        val length = text?.layoutInput?.text?.length ?: 0
        if (text == null || sentence == null || length == 0) return top..(top + coordinates.size.height)
        val first = sentence.first.coerceIn(0, length - 1)
        val last = sentence.last.coerceIn(first, length - 1)
        return (top + text.getLineTop(text.getLineForOffset(first)))..(top + text.getLineBottom(text.getLineForOffset(last)))
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle =
    when (level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        else -> ArticleBodyTextStyle.copy(fontWeight = FontWeight.SemiBold)
    }

/** A quotation: its blocks in the heading color, behind a bar on the start side. */
@Composable
private fun QuoteView(
    quote: BodyBlock.Quote,
    onLinkClick: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary
    Column(
        modifier =
            modifier
                .drawBehind {
                    val barWidth = 3.dp.toPx()
                    val barStart = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - barWidth
                    drawRect(barColor, topLeft = Offset(barStart, 0f), size = Size(barWidth, size.height))
                }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(NestedBlockSpacing),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.tertiary) {
            for (block in quote.blocks) BodyBlockView(block, onLinkClick)
        }
    }
}

@Composable
private fun ListBlockView(
    list: BodyBlock.ListBlock,
    onLinkClick: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NestedBlockSpacing)) {
        list.items.forEachIndexed { index, item ->
            Row {
                Text(
                    text = list.firstNumber?.let { "${it + index}." } ?: "•",
                    modifier = Modifier.widthIn(min = 24.dp).padding(end = 8.dp),
                    style = ArticleBodyTextStyle,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NestedBlockSpacing)) {
                    for (block in item) BodyBlockView(block, onLinkClick)
                }
            }
        }
    }
}

/** Preformatted text keeps its lines; long ones scroll sideways instead of wrapping. */
@Composable
internal fun CodeBlockView(
    code: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text = code,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            softWrap = false,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

/**
 * [text] with its styles applied. Its links call [onLinkClick] instead of opening anything.
 * [spoken], a range of the text, is marked as the sentence being read aloud.
 */
@Composable
internal fun rememberBodyText(
    text: BodyText,
    onLinkClick: (url: String) -> Unit,
    spoken: IntRange? = null,
): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainer
    val spokenBackground = MaterialTheme.colorScheme.primaryContainer
    val spokenColor = MaterialTheme.colorScheme.onPrimaryContainer
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)
    return remember(text, linkColor, codeBackground, spoken, spokenBackground, spokenColor) {
        val linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            append(text.text)
            for (range in text.ranges) {
                when (val style = range.style) {
                    InlineStyle.Emphasis -> {
                        addStyle(SpanStyle(fontStyle = FontStyle.Italic), range.start, range.end)
                    }

                    InlineStyle.Strong -> {
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.start, range.end)
                    }

                    InlineStyle.Code -> {
                        val code = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                        addStyle(code, range.start, range.end)
                    }

                    is InlineStyle.Link -> {
                        // With a listener, Compose does not open the URL itself.
                        val link = LinkAnnotation.Url(style.url, linkStyles) { currentOnLinkClick(style.url) }
                        addLink(link, range.start, range.end)
                    }
                }
            }
            val sentence = spoken?.let { it.first.coerceIn(0, text.text.length)..it.last.coerceIn(0, text.text.length - 1) }
            if (sentence != null && !sentence.isEmpty()) {
                addStyle(SpanStyle(background = spokenBackground, color = spokenColor), sentence.first, sentence.last + 1)
            }
        }
    }
}
