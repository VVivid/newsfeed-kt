package dev.vvivid.newsfeed.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Columns never grow wider than this; longer text wraps inside them. */
private val MaxColumnWidth = 240.dp

/** Columns stay at least this wide unless their text is narrower, so cells don't wrap word by word. */
private val MinColumnWidth = 96.dp

/** Space around a cell's text. */
private val CellHorizontalPadding = 12.dp
private val CellVerticalPadding = 8.dp

/**
 * A Markdown table. When its columns fit the width, it is a grid whose columns size to their text
 * the way a browser sizes them: wrapping columns narrow to fit, and the table fills the width. A
 * table too wide for the screen shows each row as a card instead, its cells under their column
 * names, because sideways scrolling hides columns and squeezed columns are unreadable.
 */
@Composable
internal fun TableView(
    table: BodyBlock.Table,
    onLinkClick: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp)
    val headerStyle = textStyle.copy(fontWeight = FontWeight.SemiBold)
    val columns = table.header.size
    val rows = listOf(table.header) + table.rows
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        // The cells' text alone, measured but never shown, decides between the grid and the cards.
        val probes =
            subcompose(TableSlot.Probe) {
                rows.forEachIndexed { rowIndex, row ->
                    for (text in row) {
                        Text(
                            text = rememberBodyText(text, onLinkClick),
                            modifier = Modifier.padding(horizontal = CellHorizontalPadding, vertical = CellVerticalPadding),
                            style = if (rowIndex == 0) headerStyle else textStyle,
                        )
                    }
                }
            }.chunked(columns)
        val maxWidth = MaxColumnWidth.roundToPx()
        val floor = MinColumnWidth.roundToPx()
        val natural =
            IntArray(columns) { column ->
                probes.maxOf { it[column].maxIntrinsicWidth(Constraints.Infinity) }.coerceAtMost(maxWidth)
            }
        val minimum =
            IntArray(columns) { column ->
                val longestWord = probes.maxOf { it[column].minIntrinsicWidth(Constraints.Infinity) }
                maxOf(longestWord, minOf(natural[column], floor)).coerceAtMost(natural[column])
            }
        val available = constraints.maxWidth
        val content =
            if (minimum.sum() <= available) {
                val widths = columnWidths(natural, minimum, available)
                subcompose(TableSlot.Grid) { TableGrid(table, widths, textStyle, headerStyle, onLinkClick) }
            } else {
                subcompose(TableSlot.Cards) { TableCards(table, textStyle, headerStyle, onLinkClick) }
            }
        val placeable = content.single().measure(constraints.copy(minWidth = 0))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}

private enum class TableSlot { Probe, Grid, Cards }

/** The table as a grid with columns [widths] wide, in a bordered box. */
@Composable
private fun TableGrid(
    table: BodyBlock.Table,
    widths: IntArray,
    textStyle: TextStyle,
    headerStyle: TextStyle,
    onLinkClick: (url: String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val lines = colors.outlineVariant
    val columns = table.header.size
    val rows = listOf(table.header) + table.rows
    Surface(shape = RoundedCornerShape(8.dp), color = colors.surface, border = BorderStroke(1.dp, lines)) {
        GridLayout(widths) {
            rows.forEachIndexed { rowIndex, row ->
                val isHeader = rowIndex == 0
                val background =
                    when {
                        isHeader -> colors.surfaceContainerHigh
                        rowIndex % 2 == 0 -> colors.surfaceContainerLow
                        else -> Color.Transparent
                    }
                row.forEachIndexed { column, text ->
                    Text(
                        text = rememberBodyText(text, onLinkClick),
                        modifier =
                            Modifier
                                .background(background)
                                .gridLines(color = lines, bottom = rowIndex < rows.lastIndex, end = column < columns - 1)
                                .padding(horizontal = CellHorizontalPadding, vertical = CellVerticalPadding),
                        textAlign = table.alignments[column].textAlign,
                        style = if (isHeader) headerStyle else textStyle,
                    )
                }
            }
        }
    }
}

/**
 * One card per body row. The row's first cell is the card's title, and every other cell sits
 * under its column's name.
 */
@Composable
private fun TableCards(
    table: BodyBlock.Table,
    textStyle: TextStyle,
    headerStyle: TextStyle,
    onLinkClick: (url: String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in table.rows) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { column, text ->
                        if (text.text.isBlank()) return@forEachIndexed
                        if (column == 0) {
                            Text(
                                text = rememberBodyText(text, onLinkClick),
                                color = colors.tertiary,
                                style = headerStyle.copy(fontSize = 16.sp),
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val name = table.header[column]
                                if (name.text.isNotBlank()) {
                                    Text(
                                        text = rememberBodyText(name, onLinkClick),
                                        color = colors.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                Text(text = rememberBodyText(text, onLinkClick), style = textStyle)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val CellAlignment.textAlign: TextAlign
    get() =
        when (this) {
            CellAlignment.Start -> TextAlign.Start
            CellAlignment.Center -> TextAlign.Center
            CellAlignment.End -> TextAlign.End
        }

/** Hairlines on a cell's bottom and end edges; the table's border draws its outer edges. */
private fun Modifier.gridLines(
    color: Color,
    bottom: Boolean,
    end: Boolean,
): Modifier =
    drawBehind {
        val width = 1.dp.toPx()
        if (bottom) drawLine(color, Offset(0f, size.height - width / 2), Offset(size.width, size.height - width / 2), width)
        if (end) {
            val x = if (layoutDirection == LayoutDirection.Ltr) size.width - width / 2 else width / 2
            drawLine(color, Offset(x, 0f), Offset(x, size.height), width)
        }
    }

/**
 * Lays out its children, the cells in row order, in columns [widths] pixels wide. Every cell in a
 * row gets the row's height, so backgrounds and grid lines line up.
 */
@Composable
private fun GridLayout(
    widths: IntArray,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, _ ->
        var top = 0
        val placedRows =
            measurables.chunked(widths.size).map { row ->
                val height = row.indices.maxOf { column -> row[column].minIntrinsicHeight(widths[column]) }
                val placeables = row.mapIndexed { column, cell -> cell.measure(Constraints.fixed(widths[column], height)) }
                (top to placeables).also { top += height }
            }
        layout(widths.sum(), top) {
            for ((rowTop, placeables) in placedRows) {
                var x = 0
                placeables.forEachIndexed { column, placeable ->
                    placeable.placeRelative(x, rowTop)
                    x += widths[column]
                }
            }
        }
    }
}

/**
 * The width of each column. [natural] is the width a column needs to show its text without
 * wrapping, and [minimum] the narrowest it may get; each minimum is at most its natural width.
 *
 * If every column fits at its natural width, the spare room is shared out in proportion so the
 * table fills [available]. Otherwise columns narrow toward their minimums, each in proportion to
 * how much it can give up. If even the minimums don't fit, they are returned unchanged.
 */
internal fun columnWidths(
    natural: IntArray,
    minimum: IntArray,
    available: Int,
): IntArray {
    val naturalTotal = natural.sumOf { it.toLong() }
    val minimumTotal = minimum.sumOf { it.toLong() }
    if (naturalTotal == 0L || minimumTotal >= available) return minimum.copyOf()
    val widths =
        if (naturalTotal <= available) {
            val spare = available - naturalTotal
            IntArray(natural.size) { natural[it] + (spare * natural[it] / naturalTotal).toInt() }
        } else {
            val room = available - minimumTotal
            val flexible = naturalTotal - minimumTotal
            IntArray(natural.size) { minimum[it] + (room * (natural[it] - minimum[it]) / flexible).toInt() }
        }
    // Rounding down leaves a few pixels over; the last column takes them so the table fills the width.
    widths[widths.lastIndex] += available - widths.sum()
    return widths
}
