package com.autom8ed.fibersocial.feed.html

/**
 * A Ravelry forum post body parsed into a renderable structure.
 *
 * Ravelry posts are authored in Markdown and rendered to HTML server-side; the API's
 * `body_html` field carries that HTML. Because the output is sanitized Markdown plus a
 * small whitelist of inline tags (`del`, `small`, `big`, `sup`, `sub`), the set of
 * possible elements is closed, and this model enumerates all of them.
 *
 * @property blocks Top-level block elements in document order.
 */
data class PostDocument(val blocks: List<PostBlock>)

/** A block-level element of a post body. */
sealed interface PostBlock {
    /** A paragraph of inline content (`<p>`, or stray inline content between blocks). */
    data class Paragraph(val content: List<Inline>) : PostBlock

    /**
     * A section heading (`<h1>`–`<h6>`).
     *
     * @property level Heading level, 1–6.
     */
    data class Heading(val level: Int, val content: List<Inline>) : PostBlock

    /** An unordered list (`<ul>`). Each item is a list of blocks to support nested lists. */
    data class BulletList(val items: List<List<PostBlock>>) : PostBlock

    /** An ordered list (`<ol>`). Each item is a list of blocks to support nested lists. */
    data class OrderedList(val items: List<List<PostBlock>>) : PostBlock

    /** A quoted passage (`<blockquote>`). */
    data class Quote(val blocks: List<PostBlock>) : PostBlock

    /**
     * A fenced or indented code block (`<pre class="fenced_code"><code>`).
     *
     * @property code Verbatim code text with original line breaks preserved.
     */
    data class CodeBlock(val code: String) : PostBlock

    /**
     * A table (`<table>`).
     *
     * @property headerRow Cells of the `<thead>` row; empty if the table has no header.
     * @property rows Body rows, each a list of cells in column order.
     */
    data class Table(val headerRow: List<TableCell>, val rows: List<List<TableCell>>) : PostBlock

    /** A horizontal rule (`<hr>`). */
    data object Divider : PostBlock
}

/**
 * One cell of a [PostBlock.Table].
 *
 * @property content Inline content of the cell.
 * @property alignment Column alignment, from the cell's `text-align` inline style
 *   (Ravelry emits `style="text-align: …"` on `<td>` for Markdown column alignment).
 */
data class TableCell(
    val content: List<Inline>,
    val alignment: CellAlignment = CellAlignment.LEFT,
)

/** Horizontal alignment of a table cell. */
enum class CellAlignment { LEFT, CENTER, RIGHT }

/** A run of inline content within a block. */
sealed interface Inline {
    /** Plain text with HTML entities decoded and whitespace runs collapsed. */
    data class Text(val text: String) : Inline

    /** Children wrapped in a presentational style (`<strong>`, `<em>`, `<del>`, …). */
    data class Styled(val style: InlineStyle, val children: List<Inline>) : Inline

    /** Inline code (`<code>` outside `<pre>`). */
    data class Code(val text: String) : Inline

    /**
     * A hyperlink (`<a>`).
     *
     * @property href Link target as it appears in the HTML; may be site-relative
     *   (e.g. `/patterns/…`) or a fragment (Markdown footnote references).
     */
    data class Link(val href: String, val children: List<Inline>) : Inline

    /**
     * An inline image (`<img>`). Markdown images arrive inside a paragraph.
     *
     * @property cssClass Raw `class` attribute value, verbatim from the source HTML
     *   (empty if absent). Ravelry marks its small inline "smiley" glyphs with a class
     *   containing `smiley`.
     * @property width Explicit `width` attribute in pixels, if present.
     * @property height Explicit `height` attribute in pixels, if present.
     */
    data class Image(
        val url: String,
        val alt: String,
        val cssClass: String = "",
        val width: Int? = null,
        val height: Int? = null,
    ) : Inline {
        /**
         * Whether this is one of Ravelry's small inline "smiley" glyphs rather than a
         * full content photo — signaled by a `smiley` class, or by an explicit size no
         * bigger than [INLINE_EMOJI_MAX_DIMENSION_PX] on both axes. Such images should
         * render inline at roughly text size instead of as a full-width block.
         */
        val isInlineEmoji: Boolean
            get() = cssClass.contains("smiley", ignoreCase = true) ||
                (
                    width != null && height != null &&
                        width <= INLINE_EMOJI_MAX_DIMENSION_PX && height <= INLINE_EMOJI_MAX_DIMENSION_PX
                    )
    }

    /** An explicit line break within a paragraph (`<br>`). */
    data object HardBreak : Inline
}

/**
 * Largest width/height (in px) an explicitly-sized image can have and still be treated as
 * an inline emoji rather than a full content photo.
 */
private const val INLINE_EMOJI_MAX_DIMENSION_PX = 30

/** Presentational styles a [Inline.Styled] span can carry. */
enum class InlineStyle {
    /** `<strong>` / `<b>` */
    BOLD,

    /** `<em>` / `<i>` */
    ITALIC,

    /** `<del>` / `<s>` / `<strike>` */
    STRIKETHROUGH,

    /** `<small>` */
    SMALL,

    /** `<big>` */
    BIG,

    /** `<sub>` */
    SUBSCRIPT,

    /** `<sup>` — also produced by Markdown footnote references. */
    SUPERSCRIPT,
}
