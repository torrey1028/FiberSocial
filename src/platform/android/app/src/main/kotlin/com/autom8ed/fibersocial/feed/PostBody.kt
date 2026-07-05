package com.autom8ed.fibersocial.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil.compose.AsyncImage
import com.autom8ed.fibersocial.feed.html.CellAlignment
import com.autom8ed.fibersocial.feed.html.Inline
import com.autom8ed.fibersocial.feed.html.InlineStyle
import com.autom8ed.fibersocial.feed.html.PostBlock
import com.autom8ed.fibersocial.feed.html.PostDocument
import com.autom8ed.fibersocial.feed.html.TableCell

/** String-annotation tag carrying a resolved link target inside built [AnnotatedString]s. */
internal const val URL_ANNOTATION = "URL"

/** Widest a table cell may grow before its text wraps. */
private val MAX_TABLE_CELL_WIDTH = 360.dp

/**
 * Renders a parsed Ravelry post body ([PostDocument]) natively: paragraphs, headings,
 * lists, quotes, horizontally scrolling code blocks and tables, dividers, and images.
 */
@Composable
fun PostBody(document: PostDocument, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        document.blocks.forEach { BlockView(it) }
    }
}

@Composable
private fun BlockView(block: PostBlock) {
    when (block) {
        is PostBlock.Paragraph -> ParagraphView(block.content)
        is PostBlock.Heading -> InlineText(
            content = block.content,
            style = headingStyle(block.level, MaterialTheme.typography),
        )
        is PostBlock.BulletList -> ListView(block.items, ordered = false)
        is PostBlock.OrderedList -> ListView(block.items, ordered = true)
        is PostBlock.Quote -> QuoteView(block.blocks)
        is PostBlock.CodeBlock -> CodeBlockView(block.code)
        is PostBlock.Table -> TableView(block)
        is PostBlock.Divider -> HorizontalDivider()
    }
}

/**
 * A paragraph's inline content, with full content photos lifted out as full-width blocks
 * between the text runs (Markdown images arrive inline, but read as standalone photos in
 * a post). Small inline "smiley" glyphs ([Inline.Image.isInlineEmoji]) are left in place
 * and rendered at text size by [InlineText] instead.
 */
@Composable
private fun ParagraphView(content: List<Inline>) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        splitOnImages(content).forEach { segment ->
            when (segment) {
                is ParagraphSegment.TextRun -> InlineText(segment.content)
                is ParagraphSegment.Photo -> {
                    val target = segment.linkHref?.let(::resolveRavelryHref)
                    AsyncImage(
                        model = segment.image.url,
                        contentDescription = segment.image.alt.ifEmpty { null },
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (target == null) Modifier
                                else Modifier.clickable {
                                    // User-generated target: no activity may handle it;
                                    // don't crash on tap (same contract as InlineText).
                                    try {
                                        uriHandler.openUri(target)
                                    } catch (e: Exception) {
                                        println("FiberSocial: couldn't open link $target: ${e.message}")
                                    }
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineText(
    content: List<Inline>,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    textAlign: TextAlign = TextAlign.Start,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val text = remember(content, linkColor, codeBackground) {
        buildInlineText(content, linkColor, codeBackground)
    }
    val uriHandler = LocalUriHandler.current
    val resolvedStyle = style.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = textAlign)

    fun openLink(offset: Int) {
        text.getStringAnnotations(URL_ANNOTATION, offset, offset)
            .firstOrNull()
            ?.let { annotation ->
                // User-generated target: no activity may handle it; don't crash on tap.
                try {
                    uriHandler.openUri(annotation.item)
                } catch (e: Exception) {
                    println("FiberSocial: couldn't open link ${annotation.item}: ${e.message}")
                }
            }
    }

    val emoji = remember(content) { collectInlineEmoji(content) }
    if (emoji.isEmpty()) {
        // No inline emoji: keep the exact original rendering path.
        ClickableText(text = text, style = resolvedStyle, onClick = ::openLink)
        return
    }

    // Emoji glyphs are embedded in `text` as inline-content placeholders (see
    // `appendInlines`), sized to roughly the surrounding line height; ClickableText has
    // no inline-content support, so link taps are handled manually here instead.
    val inlineContent = remember(emoji) {
        emoji.associate { image ->
            image.url to InlineTextContent(
                placeholder = Placeholder(
                    // em, not resolvedStyle.fontSize: a fixed Sp value wouldn't scale with
                    // a span-level style at the placeholder's actual position (<small>,
                    // <big>, super/subscript all set fontSize via SpanStyle, not the base
                    // TextStyle) and would be undefined if fontSize were ever unspecified.
                    width = 1.em,
                    height = 1.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                AsyncImage(
                    model = image.url,
                    contentDescription = image.alt.ifEmpty { null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = resolvedStyle,
        inlineContent = inlineContent,
        onTextLayout = { layoutResult = it },
        modifier = Modifier.pointerInput(text) {
            detectTapGestures { tapOffset ->
                layoutResult?.getOffsetForPosition(tapOffset)?.let(::openLink)
            }
        },
    )
}

@Composable
private fun ListView(items: List<List<PostBlock>>, ordered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { index, itemBlocks ->
            Row {
                Text(
                    text = if (ordered) "${index + 1}." else "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemBlocks.forEach { BlockView(it) }
                }
            }
        }
    }
}

@Composable
private fun QuoteView(blocks: List<PostBlock>) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline),
        )
        Column(
            modifier = Modifier.padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            blocks.forEach { BlockView(it) }
        }
    }
}

@Composable
private fun CodeBlockView(code: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
    }
}

@Composable
private fun TableView(table: PostBlock.Table) {
    val rows = buildList {
        if (table.headerRow.isNotEmpty()) add(table.headerRow)
        addAll(table.rows)
    }
    if (rows.isEmpty()) return
    val hasHeader = table.headerRow.isNotEmpty()
    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        TableGrid(rows = rows, headerRowIndex = if (hasHeader) 0 else -1)
    }
}

/**
 * Lays the cells out on a grid: every column is as wide as its widest cell (capped at
 * [MAX_TABLE_CELL_WIDTH], past which text wraps), every row as tall as its tallest cell.
 * Grid lines are drawn from the measured column/row sizes.
 */
@Composable
private fun TableGrid(rows: List<List<TableCell>>, headerRowIndex: Int) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    // Written during measure, read during draw (draw always follows measure).
    val colBounds = remember(rows) { mutableListOf<Int>() }
    val rowBounds = remember(rows) { mutableListOf<Int>() }

    Layout(
        content = {
            rows.forEachIndexed { rowIndex, row ->
                row.forEach { cell ->
                    val header = rowIndex == headerRowIndex
                    Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        InlineText(
                            content = cell.content,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                            ),
                            textAlign = when (cell.alignment) {
                                CellAlignment.LEFT -> TextAlign.Start
                                CellAlignment.CENTER -> TextAlign.Center
                                CellAlignment.RIGHT -> TextAlign.End
                            },
                        )
                    }
                }
            }
        },
        modifier = Modifier.drawBehind {
            val stroke = 1.dp.toPx()
            colBounds.forEach { x ->
                drawLine(gridColor, Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height), stroke)
            }
            rowBounds.forEach { y ->
                drawLine(gridColor, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), stroke)
            }
        },
    ) { measurables, _ ->
        val columnCount = rows.maxOf { it.size }
        val cellMaxWidth = MAX_TABLE_CELL_WIDTH.roundToPx()

        // Row-major list of measurables; rows can be ragged, so index by walking.
        val rowMeasurables = mutableListOf<List<androidx.compose.ui.layout.Measurable>>()
        var cursor = 0
        rows.forEach { row ->
            rowMeasurables += measurables.subList(cursor, cursor + row.size)
            cursor += row.size
        }

        val colWidths = IntArray(columnCount)
        rowMeasurables.forEach { row ->
            row.forEachIndexed { col, measurable ->
                val width = measurable.maxIntrinsicWidth(Int.MAX_VALUE).coerceAtMost(cellMaxWidth)
                if (width > colWidths[col]) colWidths[col] = width
            }
        }

        val placeableRows = rowMeasurables.map { row ->
            row.mapIndexed { col, measurable ->
                measurable.measure(Constraints(minWidth = colWidths[col], maxWidth = colWidths[col]))
            }
        }
        val rowHeights = placeableRows.map { row -> row.maxOf { it.height } }

        val tableWidth = colWidths.sum()
        val tableHeight = rowHeights.sum()

        colBounds.clear()
        rowBounds.clear()
        var edge = 0
        colBounds += 0
        colWidths.forEach { w -> edge += w; colBounds += edge }
        edge = 0
        rowBounds += 0
        rowHeights.forEach { h -> edge += h; rowBounds += edge }

        layout(tableWidth, tableHeight) {
            var y = 0
            placeableRows.forEachIndexed { rowIndex, row ->
                var x = 0
                row.forEachIndexed { col, placeable ->
                    placeable.place(x, y)
                    x += colWidths[col]
                }
                y += rowHeights[rowIndex]
            }
        }
    }
}

/** Maps heading levels to Material type roles, bolded to read as section headers. */
internal fun headingStyle(level: Int, typography: Typography): TextStyle {
    val base = when (level) {
        1 -> typography.headlineSmall
        2 -> typography.titleLarge
        3 -> typography.titleMedium
        else -> typography.titleSmall
    }
    return base.copy(fontWeight = FontWeight.Bold)
}

/**
 * A piece of a paragraph: either a run of text inlines (which may itself contain inline
 * emoji images) or a lifted-out full content photo.
 *
 * @property linkHref When the photo was wrapped in a link (Ravelry wraps every post
 *   photo in a link to its project page), the link's target; tapping the photo opens it.
 */
internal sealed interface ParagraphSegment {
    data class TextRun(val content: List<Inline>) : ParagraphSegment
    data class Photo(val image: Inline.Image, val linkHref: String? = null) : ParagraphSegment
}

/**
 * Splits paragraph content around full content photos so they can render as full-width
 * blocks. Photos nested one level down inside a link are lifted out the same way — they
 * keep the link target for tap handling, and any sibling text inside the link stays a
 * linked text run. Small inline emoji ([Inline.Image.isInlineEmoji]) stay merged into
 * the surrounding text run instead, so they render inline at text size.
 */
internal fun splitOnImages(content: List<Inline>): List<ParagraphSegment> {
    val segments = mutableListOf<ParagraphSegment>()
    val run = mutableListOf<Inline>()
    fun flushRun() {
        if (run.isNotEmpty()) {
            segments += ParagraphSegment.TextRun(run.toList())
            run.clear()
        }
    }
    fun Inline.isPhoto() = this is Inline.Image && !isInlineEmoji
    content.forEach { inline ->
        when {
            inline is Inline.Image && inline.isPhoto() -> {
                flushRun()
                segments += ParagraphSegment.Photo(inline)
            }
            inline is Inline.Link && inline.children.any { it.isPhoto() } -> {
                val caption = mutableListOf<Inline>()
                fun flushCaption() {
                    if (caption.isNotEmpty()) {
                        run += Inline.Link(inline.href, caption.toList())
                        caption.clear()
                    }
                }
                inline.children.forEach { child ->
                    if (child is Inline.Image && child.isPhoto()) {
                        flushCaption()
                        flushRun()
                        segments += ParagraphSegment.Photo(child, linkHref = inline.href)
                    } else {
                        caption += child
                    }
                }
                flushCaption()
            }
            else -> run += inline
        }
    }
    flushRun()
    return segments
}

/**
 * Collects the inline emoji images present in [content], including inside styled spans
 * and links, in document order. Used to build the `inlineContent` map [InlineText] passes
 * to the underlying [Text] so each placeholder appended by `appendInlines` has a matching
 * renderer.
 */
internal fun collectInlineEmoji(content: List<Inline>): List<Inline.Image> =
    content.flatMap { inline ->
        when (inline) {
            is Inline.Image -> if (inline.isInlineEmoji) listOf(inline) else emptyList()
            is Inline.Styled -> collectInlineEmoji(inline.children)
            is Inline.Link -> collectInlineEmoji(inline.children)
            else -> emptyList()
        }
    }

/** Schemes a tapped link may open. Hrefs are user-generated, so anything else is inert. */
private val ALLOWED_LINK_SCHEMES = setOf("http", "https", "mailto")

private val URL_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

/**
 * Resolves a post link target to something the device can safely open. Site-relative
 * paths get the Ravelry origin; fragment-only links (Markdown footnote refs) and targets
 * with schemes outside [ALLOWED_LINK_SCHEMES] (`javascript:`, `intent:`, …) resolve to
 * nothing and render as styled but inert text.
 */
internal fun resolveRavelryHref(href: String): String? {
    val scheme = URL_SCHEME.find(href)?.groupValues?.get(1)?.lowercase()
    return when {
        href.startsWith("#") -> null
        href.startsWith("/") -> "https://www.ravelry.com$href"
        scheme != null -> href.takeIf { scheme in ALLOWED_LINK_SCHEMES }
        else -> null
    }
}

/**
 * Builds the [AnnotatedString] for a run of inline content. Links are colored and carry
 * a [URL_ANNOTATION] with the resolved target; inline code gets a monospace chip look.
 */
internal fun buildInlineText(
    content: List<Inline>,
    linkColor: Color,
    codeBackground: Color,
): AnnotatedString = buildAnnotatedString { appendInlines(content, linkColor, codeBackground) }

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlines(
    content: List<Inline>,
    linkColor: Color,
    codeBackground: Color,
) {
    content.forEach { inline ->
        when (inline) {
            is Inline.Text -> append(inline.text)
            is Inline.HardBreak -> append('\n')
            is Inline.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, fontSize = 0.9.em),
            ) { append(inline.text) }
            is Inline.Styled -> withStyle(spanStyleFor(inline.style)) {
                appendInlines(inline.children, linkColor, codeBackground)
            }
            is Inline.Link -> {
                val target = resolveRavelryHref(inline.href)
                if (target != null) pushStringAnnotation(URL_ANNOTATION, target)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    appendInlines(inline.children, linkColor, codeBackground)
                }
                if (target != null) pop()
            }
            // Inline emoji are rendered in place via an inline-content placeholder (see
            // `collectInlineEmoji`/`InlineText`). Full content photos are lifted out at
            // the paragraph level instead (including one level inside links, see
            // `splitOnImages`); ignore any that appear deeper here (e.g. a table cell).
            is Inline.Image -> if (inline.isInlineEmoji) {
                appendInlineContent(inline.url, inline.alt.ifEmpty { "emoji" })
            }
        }
    }
}

private fun spanStyleFor(style: InlineStyle): SpanStyle = when (style) {
    InlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    InlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    InlineStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    InlineStyle.SMALL -> SpanStyle(fontSize = 0.8.em)
    InlineStyle.BIG -> SpanStyle(fontSize = 1.2.em)
    InlineStyle.SUBSCRIPT -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.8.em)
    InlineStyle.SUPERSCRIPT -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.8.em)
}
