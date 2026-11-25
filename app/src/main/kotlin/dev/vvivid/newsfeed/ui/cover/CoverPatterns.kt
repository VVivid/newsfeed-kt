package dev.vvivid.newsfeed.ui.cover

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.vvivid.newsfeed.ui.theme.CoverPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

// Compose ports of the CSS backgrounds in the design's cover component. Each pattern draws its
// layers from the bottom up. Pattern sizes scale with `unit` (see coverUnit); line widths that the
// design gives in CSS pixels are in dp here.
//
// Two angle conventions meet in this file, both clockwise: CSS gradient angles (drawStripes,
// gradientLine) start at 12 o'clock, while Compose arc angles (drawArc) start at 3 o'clock.

internal fun DrawScope.drawCoverPattern(
    pattern: CoverPattern,
    palette: CoverPalette,
) {
    val unit = coverUnit()
    when (pattern) {
        CoverPattern.Halftone -> drawHalftone(palette, unit)
        CoverPattern.Hatch -> drawHatch(palette, unit)
        CoverPattern.Ruled -> drawRuled(palette, unit)
        CoverPattern.CornerArcs -> drawCornerArcs(palette, unit)
        CoverPattern.Sunburst -> drawSunburst(palette)
        CoverPattern.Checker -> drawChecker(palette, unit)
        CoverPattern.GridPaper -> drawGridPaper(palette, unit)
        CoverPattern.Bauhaus -> drawBauhaus(palette)
        CoverPattern.Scallop -> drawScallop(palette, unit)
        CoverPattern.Awning -> drawAwning(palette, unit)
        CoverPattern.Stagger -> drawStagger(palette, unit)
        CoverPattern.Zigzag -> drawZigzag(palette, unit)
        CoverPattern.Arches -> drawArches(palette, unit)
        CoverPattern.Crosshatch -> drawCrosshatch(palette, unit)
        CoverPattern.Quarters -> drawQuarters(palette, unit)
        CoverPattern.Horizon -> drawHorizon(palette)
        CoverPattern.Target -> drawTarget(palette, unit)
        CoverPattern.Plaid -> drawPlaid(palette, unit)
        CoverPattern.Split -> drawSplit(palette)
        CoverPattern.Columns -> drawColumns(palette, unit)
    }
}

/** The design's pattern scale: a fifth of the shorter side in whole dp, never below 14 dp. */
private fun DrawScope.coverUnit(): Float {
    val shortSideDp = size.minDimension / density
    return max(14f, (shortSideDp / 5f).roundToInt().toFloat()).dp.toPx()
}

// 01 Halftone: brown dots on cream, fading out toward the bottom-right corner.
private fun DrawScope.drawHalftone(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.cream)
    val spacing = unit / 2
    drawDotGrid(c.brown, radius = discRadius(unit * 0.17f), spacing = spacing, firstCenter = Offset(spacing / 2, spacing / 2))
    val fade = gradientLine(angleDegrees = 135f)
    // Fade from cream with zero alpha, not Color.Transparent, so the blend never darkens.
    drawRect(Brush.linearGradient(0.15f to c.cream.copy(alpha = 0f), 0.92f to c.cream, start = fade.start, end = fade.end))
}

// 02 Hatch: thin clay diagonals on rose.
private fun DrawScope.drawHatch(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.rose)
    drawStripes(c.clay, angleDegrees = 45f, period = unit / 3, width = 1.5.dp.toPx())
}

// 03 Ruled: notebook paper with faint olive lines and a clay margin rule.
private fun DrawScope.drawRuled(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.cream)
    val lineSpacing = unit / 3
    val line = 1.dp.toPx()
    drawStripes(c.olive.copy(alpha = 0.55f), angleDegrees = 180f, period = lineSpacing, width = line, offset = lineSpacing - line)
    drawRect(c.clay, topLeft = Offset(unit * 0.8f, 0f), size = Size(1.5.dp.toPx(), size.height))
}

// 04 Corner arcs: olive rings spreading from the bottom-left corner.
private fun DrawScope.drawCornerArcs(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.sage)
    drawRings(c.olive, center = Offset(0f, size.height), spacing = unit / 2.5f, width = 2.dp.toPx())
}

// 05 Sunburst: mustard rays fanning up from the middle of the bottom edge.
private fun DrawScope.drawSunburst(c: CoverPalette) {
    drawRect(c.sand)
    val origin = Offset(size.width / 2, size.height)
    val radius = farthestCornerDistance(origin)
    // Twelve 7.5° rays, one every 15°, across the upper half. Compose measures arc angles
    // clockwise from 3 o'clock, so 180° points left.
    for (ray in 0 until 12) {
        drawArc(
            color = c.mustard,
            startAngle = 180f + ray * 15f,
            sweepAngle = 7.5f,
            useCenter = true,
            topLeft = origin - Offset(radius, radius),
            size = Size(radius * 2, radius * 2),
        )
    }
}

// 06 Checker: rose and cream squares, half a unit wide.
private fun DrawScope.drawChecker(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.cream)
    val half = unit / 2
    val square = Size(half, half)
    forEachTile(unit, unit) { tile ->
        drawRect(c.rose, topLeft = tile + Offset(half, 0f), size = square)
        drawRect(c.rose, topLeft = tile + Offset(0f, half), size = square)
    }
}

// 07 Grid paper: a faint olive grid on cream.
private fun DrawScope.drawGridPaper(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.cream)
    val gridColor = c.olive.copy(alpha = 0.45f)
    val spacing = unit * 0.6f
    val line = 1.dp.toPx()
    drawStripes(gridColor, angleDegrees = 90f, period = spacing, width = line)
    drawStripes(gridColor, angleDegrees = 180f, period = spacing, width = line)
}

// 08 Bauhaus: a mustard band on the left, a wine band along the bottom and a clay circle.
private fun DrawScope.drawBauhaus(c: CoverPalette) {
    drawRect(c.sand)
    drawRect(c.mustard, size = Size(size.width * 0.18f, size.height))
    drawRect(c.wine, topLeft = Offset(0f, size.height * 0.78f), size = Size(size.width, size.height * 0.22f))
    drawCircle(c.clay, radius = discRadius(size.minDimension * 0.24f), center = Offset(size.width * 0.66f, size.height * 0.4f))
}

// 09 Scallop: rows of olive half-rings hanging from each row's top edge.
private fun DrawScope.drawScallop(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.sage)
    // The ring runs from 0.30 to 0.38 units out from its center.
    val radius = unit * 0.34f
    forEachTile(unit, unit / 2) { tile ->
        val center = tile + Offset(unit / 2, 0f)
        drawArc(
            color = c.olive,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = center - Offset(radius, radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = unit * 0.08f),
        )
    }
}

// 10 Awning: wide cream and rose vertical stripes.
private fun DrawScope.drawAwning(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.rose)
    drawStripes(c.cream, angleDegrees = 90f, period = unit, width = unit / 2)
}

// 11 Stagger: small wine dots on a staggered grid.
private fun DrawScope.drawStagger(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.rose)
    val spacing = unit / 1.5f
    val radius = discRadius(max(1.5.dp.toPx(), unit * 0.07f))
    drawDotGrid(c.wine, radius = radius, spacing = spacing, firstCenter = Offset(spacing / 2, spacing / 2))
    drawDotGrid(c.wine, radius = radius, spacing = spacing, firstCenter = Offset.Zero)
}

// 12 Zigzag: mustard chevron rows. Each tile has a triangle hanging from its top edge and two
// half-triangles in its bottom corners, which join their neighbors into upward triangles.
private fun DrawScope.drawZigzag(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.cream)
    val tile = unit / 1.5f
    val half = tile / 2
    val triangles = Path()
    forEachTile(tile, tile) { (left, top) ->
        val right = left + tile
        val bottom = top + tile
        triangles.addTriangle(Offset(left, top), Offset(right, top), Offset(left + half, top + half))
        triangles.addTriangle(Offset(left, bottom), Offset(left + half, bottom), Offset(left, top + half))
        triangles.addTriangle(Offset(right, bottom), Offset(right - half, bottom), Offset(right, top + half))
    }
    drawPath(triangles, c.mustard)
}

// 13 Arches: rows of brown half-discs standing on each row's bottom edge.
private fun DrawScope.drawArches(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.sand)
    val radius = discRadius(unit * 0.4f)
    forEachTile(unit, unit / 2) { tile ->
        val baseCenter = tile + Offset(unit / 2, unit / 2)
        drawArc(
            color = c.brown,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = baseCenter - Offset(radius, radius),
            size = Size(radius * 2, radius * 2),
        )
    }
}

// 14 Crosshatch: two sets of faint olive diagonals.
private fun DrawScope.drawCrosshatch(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.sage)
    val hatchColor = c.olive.copy(alpha = 0.6f)
    val line = 1.dp.toPx()
    drawStripes(hatchColor, angleDegrees = -45f, period = unit / 3, width = line)
    drawStripes(hatchColor, angleDegrees = 45f, period = unit / 3, width = line)
}

// 15 Quarters: each tile has a rose quarter-disc in its bottom-right corner and a clay one in its
// top-left corner.
private fun DrawScope.drawQuarters(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.cream)
    val radius = discRadius(unit / 2)
    forEachTile(unit, unit) { tile ->
        // Compose measures arc angles clockwise from 3 o'clock: 180° opens up-left, 0° down-right.
        drawQuarterDisc(c.rose, corner = tile + Offset(unit, unit), radius = radius, startAngle = 180f)
        drawQuarterDisc(c.clay, corner = tile, radius = radius, startAngle = 0f)
    }
}

// 16 Horizon: a mustard sun setting behind an olive horizon line and sage ground.
private fun DrawScope.drawHorizon(c: CoverPalette) {
    drawRect(c.cream)
    val horizonY = size.height * 0.6f
    drawCircle(c.mustard, radius = discRadius(size.minDimension * 0.22f), center = Offset(size.width / 2, horizonY))
    val line = 2.dp.toPx()
    drawRect(c.olive, topLeft = Offset(0f, horizonY), size = Size(size.width, line))
    drawRect(c.sage, topLeft = Offset(0f, horizonY + line), size = Size(size.width, size.height - horizonY - line))
}

// 17 Target: clay rings around the center.
private fun DrawScope.drawTarget(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.rose)
    drawRings(c.clay, center = center, spacing = unit / 2.5f, width = 1.5.dp.toPx())
}

// 18 Plaid: translucent clay bands both ways, crossed by thin wine lines.
private fun DrawScope.drawPlaid(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.sand)
    val band = c.clay.copy(alpha = 0.25f)
    val bandWidth = unit * 0.35f
    // At 0° the bands repeat upward from the bottom edge, as CSS measures them.
    drawStripes(band, angleDegrees = 0f, period = unit, width = bandWidth)
    drawStripes(band, angleDegrees = 90f, period = unit, width = bandWidth)
    val thread = 1.5.dp.toPx()
    drawStripes(c.wine.copy(alpha = 0.45f), angleDegrees = 90f, period = unit, width = thread, offset = unit - thread)
}

// 19 Split: rose and clay halves divided by a 45° line through the center, with a small cream moon.
private fun DrawScope.drawSplit(c: CoverPalette) {
    drawRect(c.rose)
    // A triangle reaching past every edge whose long side is the 45° line through the center;
    // the canvas clips it to the lower-right half.
    val reach = size.width + size.height
    val lowerRightHalf =
        Path().apply {
            addTriangle(
                center + Offset(reach, -reach),
                center + Offset(-reach, reach),
                center + Offset(reach, reach),
            )
        }
    drawPath(lowerRightHalf, c.clay)
    drawCircle(c.cream, radius = discRadius(size.minDimension * 0.1f), center = Offset(size.width * 0.72f, size.height * 0.3f))
}

// 20 Columns: a newspaper page of brown text lines in columns separated by thin rules.
private fun DrawScope.drawColumns(
    c: CoverPalette,
    unit: Float,
) {
    drawRect(c.cream)
    val lineHeight = max(4.dp.toPx(), unit * 0.22f)
    drawStripes(c.brown.copy(alpha = 0.5f), angleDegrees = 180f, period = lineHeight, width = lineHeight * 0.45f)
    val columnWidth = unit * 1.3f
    val gutter = max(6.dp.toPx(), unit * 0.3f)
    val period = columnWidth + gutter
    // Cream gutters cover the text lines between columns; a brown rule runs down each gutter.
    drawStripes(c.cream, angleDegrees = 90f, period = period, width = gutter, offset = columnWidth)
    val rule = 1.dp.toPx()
    drawStripes(c.brown, angleDegrees = 90f, period = period, width = rule, offset = columnWidth + (gutter - rule) / 2)
}

/**
 * The line CSS uses for linear-gradient(angle): through the center, pointing at [angleDegrees]
 * (0 = up, clockwise), just long enough for the farthest corners to project onto its ends.
 */
private class GradientLine(
    val start: Offset,
    val direction: Offset,
    val length: Float,
) {
    val end: Offset get() = start + direction * length
}

private fun DrawScope.gradientLine(angleDegrees: Float): GradientLine {
    val radians = angleDegrees * PI / 180
    val direction = Offset(sin(radians).toFloat(), -cos(radians).toFloat())
    val length = abs(size.width * direction.x) + abs(size.height * direction.y)
    return GradientLine(start = center - direction * (length / 2), direction = direction, length = length)
}

/**
 * Parallel stripes, the equivalent of CSS repeating-linear-gradient(angleDegrees, ...): measured
 * along the gradient line from its start, every [period] holds one [width]-wide stripe of [color]
 * beginning [offset] into the period. Stripes cross the gradient line at right angles, so 90°
 * (pointing right) draws vertical stripes and 180° (pointing down) horizontal ones.
 */
private fun DrawScope.drawStripes(
    color: Color,
    angleDegrees: Float,
    period: Float,
    width: Float,
    offset: Float = 0f,
) {
    val line = gradientLine(angleDegrees)
    val alongStripe = Offset(-line.direction.y, line.direction.x)
    // From any point on the gradient line, this reaches past every edge of the canvas.
    val halfStripeLength = size.width + size.height
    var stripeStart = offset
    while (stripeStart < line.length) {
        val stripeCenter = line.start + line.direction * (stripeStart + width / 2)
        drawLine(
            color = color,
            start = stripeCenter - alongStripe * halfStripeLength,
            end = stripeCenter + alongStripe * halfStripeLength,
            strokeWidth = width,
        )
        stripeStart += period
    }
}

/**
 * Concentric rings, the equivalent of CSS repeating-radial-gradient: every [spacing] out from
 * [center], a [width]-wide ring of [color]. The first ring is a dot on the center.
 */
private fun DrawScope.drawRings(
    color: Color,
    center: Offset,
    spacing: Float,
    width: Float,
) {
    val farthest = farthestCornerDistance(center)
    var innerRadius = 0f
    while (innerRadius < farthest) {
        drawCircle(color, radius = innerRadius + width / 2, center = center, style = Stroke(width))
        innerRadius += spacing
    }
}

/**
 * Radius to draw for one of the design's discs. Its CSS radial gradients fade out over the 0.6 px
 * beyond [radius], so a hard edge halfway through that fade has the same visible size.
 */
private fun DrawScope.discRadius(radius: Float): Float = radius + 0.3.dp.toPx()

/** A quarter disc around [corner], spanning 90° clockwise from [startAngle]. */
private fun DrawScope.drawQuarterDisc(
    color: Color,
    corner: Offset,
    radius: Float,
    startAngle: Float,
) {
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = 90f,
        useCenter = true,
        topLeft = corner - Offset(radius, radius),
        size = Size(radius * 2, radius * 2),
    )
}

/** Dots of [radius] every [spacing] in both directions, starting at [firstCenter]. */
private fun DrawScope.drawDotGrid(
    color: Color,
    radius: Float,
    spacing: Float,
    firstCenter: Offset,
) {
    var y = firstCenter.y
    while (y - radius < size.height) {
        var x = firstCenter.x
        while (x - radius < size.width) {
            drawCircle(color, radius = radius, center = Offset(x, y))
            x += spacing
        }
        y += spacing
    }
}

/**
 * Calls [drawTile] with the top-left corner of every tile that overlaps the canvas, tiling from
 * the canvas origin like a CSS background-size.
 */
private inline fun DrawScope.forEachTile(
    tileWidth: Float,
    tileHeight: Float,
    drawTile: (topLeft: Offset) -> Unit,
) {
    var top = 0f
    while (top < size.height) {
        var left = 0f
        while (left < size.width) {
            drawTile(Offset(left, top))
            left += tileWidth
        }
        top += tileHeight
    }
}

private fun DrawScope.farthestCornerDistance(point: Offset): Float =
    listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
        .maxOf { corner -> (corner - point).getDistance() }

private fun Path.addTriangle(
    a: Offset,
    b: Offset,
    c: Offset,
) {
    moveTo(a.x, a.y)
    lineTo(b.x, b.y)
    lineTo(c.x, c.y)
    close()
}
