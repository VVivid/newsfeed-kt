package dev.vvivid.newsfeed.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import dev.vvivid.newsfeed.R
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
            CodeBlockView(block.code, blockModifier, language = block.language)
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

/**
 * Preformatted text keeps its lines; long ones scroll sideways instead of wrapping. Code in a
 * [language] the reader knows is colored by its syntax (see [highlightCode]); a header names the
 * language and copies the code.
 */
@Composable
internal fun CodeBlockView(
    code: String,
    modifier: Modifier = Modifier,
    language: String? = null,
) {
    val context = LocalContext.current
    val palette = if (MaterialTheme.colorScheme.surface.luminance() < HALF_LUMINANCE) DarkCodeColors else LightCodeColors
    val text = remember(code, language, palette) { coloredCode(code, language, palette) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = codeLanguageName(language).orEmpty(),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(onClick = { copyCode(context, code) }) {
                    Icon(
                        painterResource(R.drawable.ic_content_copy),
                        contentDescription = stringResource(R.string.code_copy),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = text,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                softWrap = false,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

/** Puts [code] on the clipboard. Android 13 and later confirm that themselves. */
private fun copyCode(
    context: Context,
    code: String,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.code_copy), code))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.code_copied, Toast.LENGTH_SHORT).show()
    }
}

/** The colors of each kind of code token, for a light or a dark background. */
internal class CodeColors(
    val keyword: Color,
    val type: Color,
    val function: Color,
    val text: Color,
    val comment: Color,
    val punctuation: Color,
)

// Each passes 4.5:1 on the reader's code background in its theme.
private val DarkCodeColors =
    CodeColors(
        keyword = Color(0xFFE7B4F0),
        type = Color(0xFF84D6C2),
        function = Color(0xFF9EC2FF),
        text = Color(0xFFF0C38A),
        comment = Color(0xFF9A9DA9),
        punctuation = Color(0xFFA3A6B2),
    )

private val LightCodeColors =
    CodeColors(
        keyword = Color(0xFF8B2F9E),
        type = Color(0xFF146B5A),
        function = Color(0xFF2457B8),
        text = Color(0xFF8A510F),
        comment = Color(0xFF63656F),
        punctuation = Color(0xFF5E6070),
    )

private const val HALF_LUMINANCE = 0.5f

/** [code] with each token of [language] in its color; comments are also italic. */
internal fun coloredCode(
    code: String,
    language: String?,
    colors: CodeColors,
): AnnotatedString =
    buildAnnotatedString {
        append(code)
        for (token in highlightCode(code, language)) {
            val style =
                when (token.kind) {
                    CodeTokenKind.Keyword -> SpanStyle(color = colors.keyword)
                    CodeTokenKind.Type -> SpanStyle(color = colors.type)
                    CodeTokenKind.Function -> SpanStyle(color = colors.function)
                    CodeTokenKind.Text, CodeTokenKind.Number -> SpanStyle(color = colors.text)
                    CodeTokenKind.Comment -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
                    CodeTokenKind.Punctuation -> SpanStyle(color = colors.punctuation)
                }
            addStyle(style, token.start, token.end)
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
