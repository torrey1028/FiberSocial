package com.autom8ed.fibersocial.feed.html

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * Parses Ravelry `body_html` into a [PostDocument].
 *
 * The parser is deliberately lenient: unknown block-level tags are unwrapped and their
 * children parsed in place, and unknown inline tags degrade to their inner content, so a
 * post never disappears outright if Ravelry's renderer output shifts.
 */
object HtmlPostParser {

    /** Tags with dedicated block handling in [parseBlock]. */
    private val BLOCK_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "blockquote", "pre", "table", "hr", "div",
    )

    /**
     * Tags with dedicated inline handling in [parseInlineNode]. An element at block
     * position that is in neither set is treated as an unknown block container and
     * unwrapped, so nested structure survives (e.g. paragraphs inside a `<section>`).
     */
    private val INLINE_TAGS = setOf(
        "strong", "b", "em", "i", "del", "s", "strike", "small", "big",
        "sub", "sup", "code", "a", "img", "br", "span",
    )

    /** Parses [bodyHtml] (the API's `body_html` field) into a structured document. */
    fun parse(bodyHtml: String): PostDocument =
        PostDocument(parseBlocks(Ksoup.parse(bodyHtml).body()))

    /**
     * Parses the children of [container] as a sequence of blocks. Consecutive inline
     * content (text nodes, `<strong>`, bare `<br>`, …) is gathered into an implicit
     * paragraph, which also handles "tight" Markdown list items whose `<li>` holds
     * inline content directly.
     */
    private fun parseBlocks(container: Element): List<PostBlock> {
        val blocks = mutableListOf<PostBlock>()
        val pendingInline = mutableListOf<Inline>()

        fun flushParagraph() {
            val content = trimEdges(pendingInline)
            if (content.isNotEmpty()) blocks += PostBlock.Paragraph(content)
            pendingInline.clear()
        }

        for (node in container.childNodes()) {
            val element = node as? Element
            when {
                element != null && element.tagName() in BLOCK_TAGS -> {
                    flushParagraph()
                    blocks += parseBlock(element)
                }
                element != null && element.tagName() !in INLINE_TAGS -> {
                    // Unknown container at block position: unwrap in place.
                    flushParagraph()
                    blocks += parseBlocks(element)
                }
                else -> pendingInline += parseInlineNode(node)
            }
        }
        flushParagraph()
        return blocks
    }

    /** Parses one block element. Returns a list because unknown wrappers may unwrap to several. */
    private fun parseBlock(element: Element): List<PostBlock> = when (element.tagName()) {
        "p" -> listOfNotNull(
            trimEdges(parseInlineChildren(element))
                .takeIf { it.isNotEmpty() }
                ?.let { PostBlock.Paragraph(it) }
        )
        "h1", "h2", "h3", "h4", "h5", "h6" -> listOf(
            PostBlock.Heading(
                level = element.tagName().substring(1).toInt(),
                content = trimEdges(parseInlineChildren(element)),
            )
        )
        "ul" -> listOf(PostBlock.BulletList(parseListItems(element)))
        "ol" -> listOf(PostBlock.OrderedList(parseListItems(element)))
        "blockquote" -> listOf(PostBlock.Quote(parseBlocks(element)))
        "pre" -> listOf(PostBlock.CodeBlock(codeText(element)))
        "table" -> listOf(parseTable(element))
        "hr" -> listOf(PostBlock.Divider)
        // Wrapper divs (e.g. the site's `div.forum_post_body`) and future unknown
        // containers: parse children in place.
        else -> parseBlocks(element)
    }

    private fun parseListItems(list: Element): List<List<PostBlock>> =
        list.children()
            .filter { it.tagName() == "li" }
            .map { parseBlocks(it) }

    /** Extracts verbatim code from a `<pre>`, preferring its inner `<code>` if present. */
    private fun codeText(pre: Element): String {
        val code = pre.children().firstOrNull { it.tagName() == "code" } ?: pre
        return code.wholeText().trim('\n')
    }

    private fun parseTable(table: Element): PostBlock.Table {
        val headerRow = table.selectFirst("thead tr")
            ?.children()
            ?.filter { it.tagName() == "th" || it.tagName() == "td" }
            ?.map { parseCell(it) }
            .orEmpty()
        val rows = table.select("tr")
            .filter { tr -> tr.parents().none { it.tagName() == "thead" } }
            .map { tr ->
                tr.children()
                    .filter { it.tagName() == "td" || it.tagName() == "th" }
                    .map { parseCell(it) }
            }
            .filter { it.isNotEmpty() }
        return PostBlock.Table(headerRow = headerRow, rows = rows)
    }

    private fun parseCell(cell: Element): TableCell = TableCell(
        content = trimEdges(parseInlineChildren(cell)),
        alignment = cellAlignment(cell),
    )

    private fun cellAlignment(cell: Element): CellAlignment {
        val style = cell.attr("style")
        return when {
            style.contains("text-align: center") -> CellAlignment.CENTER
            style.contains("text-align: right") -> CellAlignment.RIGHT
            else -> CellAlignment.LEFT
        }
    }

    private fun parseInlineChildren(element: Element): List<Inline> =
        element.childNodes().flatMap { parseInlineNode(it) }

    /** Parses one node in inline position. Returns a list: unknown tags unwrap to children. */
    private fun parseInlineNode(node: Node): List<Inline> = when {
        node is TextNode -> {
            val text = collapseWhitespace(node.getWholeText())
            if (text.isEmpty()) emptyList() else listOf(Inline.Text(text))
        }
        node !is Element -> emptyList() // comments etc.
        else -> when (val tag = node.tagName()) {
            "strong", "b" -> styled(InlineStyle.BOLD, node)
            "em", "i" -> styled(InlineStyle.ITALIC, node)
            "del", "s", "strike" -> styled(InlineStyle.STRIKETHROUGH, node)
            "small" -> styled(InlineStyle.SMALL, node)
            "big" -> styled(InlineStyle.BIG, node)
            "sub" -> styled(InlineStyle.SUBSCRIPT, node)
            "sup" -> styled(InlineStyle.SUPERSCRIPT, node)
            "code" -> listOf(Inline.Code(node.text()))
            "a" -> listOf(Inline.Link(href = node.attr("href"), children = parseInlineChildren(node)))
            "img" -> listOf(Inline.Image(url = node.attr("src"), alt = node.attr("alt")))
            "br" -> listOf(Inline.HardBreak)
            // Purely presentational wrapper; unwrap without noise.
            "span" -> parseInlineChildren(node)
            else -> {
                // Once per parse of an affected post (parses are remember-cached by
                // callers); flags Ravelry renderer drift worth adding support for.
                println("FiberSocial: HtmlPostParser unwrapping unknown inline tag <$tag>")
                parseInlineChildren(node)
            }
        }
    }

    private fun styled(style: InlineStyle, element: Element): List<Inline> =
        listOf(Inline.Styled(style, parseInlineChildren(element)))

    /**
     * Collapses whitespace runs (including the newlines HTML source wraps at) to single
     * spaces. Interior single spaces between inline elements are preserved.
     */
    private fun collapseWhitespace(text: String): String {
        if (text.isBlank()) return if (text.isEmpty()) "" else " "
        return text.replace(WHITESPACE_RUN, " ")
    }

    private val WHITESPACE_RUN = Regex("\\s+")

    /** Strips leading/trailing whitespace at the edges of a block's inline content. */
    private fun trimEdges(content: List<Inline>): List<Inline> {
        val result = content.toMutableList()
        (result.firstOrNull() as? Inline.Text)?.let {
            val trimmed = it.text.trimStart()
            if (trimmed.isEmpty()) result.removeAt(0) else result[0] = Inline.Text(trimmed)
        }
        (result.lastOrNull() as? Inline.Text)?.let {
            val trimmed = it.text.trimEnd()
            if (trimmed.isEmpty()) result.removeAt(result.lastIndex) else result[result.lastIndex] = Inline.Text(trimmed)
        }
        return result
    }
}
