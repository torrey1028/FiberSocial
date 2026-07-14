package com.myhobbyislearning.fibersocial.feed.html

import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Post
import com.fleeksoft.ksoup.Ksoup
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Parses a Ravelry post's raw Markdown source (`body`) into a [PostDocument].
 *
 * Ravelry posts are authored in Markdown; the API exposes both the source (`body`) and a
 * server-side rendering (`body_html`). The rendering is not trustworthy: it has been
 * observed to silently drop image paragraphs that exist in the source (issue #102), so
 * the source is the canonical content field. Rather than mapping the Markdown AST to
 * [PostDocument] directly, this renders Markdown to HTML locally and reuses
 * [HtmlPostParser], so both content paths funnel through one battle-tested converter
 * (tables, lists, code, emoji sizing, URL resolution all behave identically).
 */
object MarkdownPostParser {

    // GFM (not plain CommonMark): Ravelry posts use strikethrough, tables, and autolinks.
    private val flavour = GFMFlavourDescriptor()

    /**
     * Parses Markdown source into a renderable [PostDocument].
     *
     * @param markdown Raw Markdown, as found in the API's `body` field. May contain
     *   CRLF line endings (normalized here — the parser expects LF).
     * @param renderedHtml The API's `body_html` for the same post, if available. Used
     *   only to resolve emoji: the Markdown source spells emoji as `:shortcode:` tokens,
     *   and the shortcode → Twemoji-image mapping exists nowhere but Ravelry's rendering
     *   (`<img class="emo" alt=":purple_heart:" src="…/twemoji/1f49c.png">`). Shortcodes
     *   Ravelry didn't render (or whose paragraph it dropped) stay literal text — gating
     *   on the rendering means plain text like `10:30-11:00` can never become an image.
     */
    fun parse(markdown: String, renderedHtml: String = ""): PostDocument {
        val normalized = markdown.replace("\r\n", "\n")
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(normalized)
        val html = HtmlGenerator(normalized, tree, flavour).generateHtml()
        val document = HtmlPostParser.parse(html)
        val emoji = harvestEmoji(renderedHtml)
        return if (emoji.isEmpty()) document else document.substituteEmoji(emoji)
    }

    /**
     * Flattens Markdown to plain text for one-line previews: styling unwrapped, links
     * reduced to their text, images contributing nothing. Emphasis markers the parser
     * left literal (Ravelry truncates topic summaries mid-syntax, so an opening `**`
     * may never close — issue #104) are dropped in a final cleanup pass.
     */
    fun plainText(markdown: String): String =
        parse(markdown).blocks
            .joinToString(" ") { blockPlainText(it) }
            .replace(LITERAL_EMPHASIS_RUN, "")
            .replace(WHITESPACE_RUN, " ")
            .trim()

    // Paired emphasis is consumed by the parser; runs surviving to the text are the
    // unclosed leftovers, which always sit at a word boundary (start-of-text or
    // whitespace) immediately before more text — unlike a literal asterisk used as
    // punctuation (e.g. "C*", "3*4"), which sits directly against other characters.
    // Underscores stay — they're common inside usernames.
    private val LITERAL_EMPHASIS_RUN = Regex("(?<=^|\\s)\\*+(?=\\S)")
    private val WHITESPACE_RUN = Regex("\\s+")

    private fun blockPlainText(block: PostBlock): String = when (block) {
        is PostBlock.Paragraph -> inlinePlainText(block.content)
        is PostBlock.Heading -> inlinePlainText(block.content)
        is PostBlock.BulletList -> block.items.joinToString(" ") { item ->
            item.joinToString(" ") { blockPlainText(it) }
        }
        is PostBlock.OrderedList -> block.items.joinToString(" ") { item ->
            item.joinToString(" ") { blockPlainText(it) }
        }
        is PostBlock.Quote -> block.blocks.joinToString(" ") { blockPlainText(it) }
        is PostBlock.CodeBlock -> block.code
        is PostBlock.Table -> (listOf(block.headerRow) + block.rows).joinToString(" ") { row ->
            row.joinToString(" ") { inlinePlainText(it.content) }
        }
        is PostBlock.Divider -> ""
    }

    private fun inlinePlainText(content: List<Inline>): String = content.joinToString("") {
        when (it) {
            is Inline.Text -> it.text
            is Inline.Styled -> inlinePlainText(it.children)
            is Inline.Code -> it.text
            is Inline.Link -> inlinePlainText(it.children)
            is Inline.Image -> ""
            is Inline.HardBreak -> " "
        }
    }

    // Broad on purpose: only tokens that match a harvested alt ever substitute.
    private val SHORTCODE = Regex(":[A-Za-z0-9_+-]+:")

    /**
     * Extracts Ravelry's emoji renderings from `body_html`, keyed by `:shortcode:`.
     * Live capture: `<img alt="purple_heart" title=":purple_heart:" class="emo" …>` —
     * the colon-wrapped token lives in `title`; `alt` carries the bare name.
     */
    private fun harvestEmoji(renderedHtml: String): Map<String, Inline.Image> {
        if (renderedHtml.isBlank() || !renderedHtml.contains("emo")) return emptyMap()
        return Ksoup.parse(renderedHtml).select("img.emo")
            .mapNotNull { img ->
                val shortcode = img.attr("title").takeIf { SHORTCODE.matches(it) }
                    ?: img.attr("alt").takeIf { it.isNotBlank() }
                        ?.let { ":$it:" }?.takeIf { SHORTCODE.matches(it) }
                val src = img.attr("src")
                if (shortcode != null && src.isNotBlank()) {
                    shortcode to Inline.Image(url = src, alt = shortcode, cssClass = img.attr("class"))
                } else {
                    null
                }
            }
            .toMap()
    }

    private fun PostDocument.substituteEmoji(emoji: Map<String, Inline.Image>): PostDocument =
        PostDocument(blocks.map { it.substituteEmoji(emoji) })

    private fun PostBlock.substituteEmoji(emoji: Map<String, Inline.Image>): PostBlock = when (this) {
        is PostBlock.Paragraph -> copy(content = content.substituteEmoji(emoji))
        is PostBlock.Heading -> copy(content = content.substituteEmoji(emoji))
        is PostBlock.BulletList -> copy(items = items.map { item -> item.map { it.substituteEmoji(emoji) } })
        is PostBlock.OrderedList -> copy(items = items.map { item -> item.map { it.substituteEmoji(emoji) } })
        is PostBlock.Quote -> copy(blocks = blocks.map { it.substituteEmoji(emoji) })
        is PostBlock.Table -> copy(
            headerRow = headerRow.map { it.copy(content = it.content.substituteEmoji(emoji)) },
            rows = rows.map { row -> row.map { it.copy(content = it.content.substituteEmoji(emoji)) } },
        )
        is PostBlock.CodeBlock, PostBlock.Divider -> this
    }

    private fun List<Inline>.substituteEmoji(emoji: Map<String, Inline.Image>): List<Inline> =
        flatMap { inline ->
            when (inline) {
                is Inline.Text -> splitShortcodes(inline.text, emoji)
                is Inline.Styled -> listOf(inline.copy(children = inline.children.substituteEmoji(emoji)))
                is Inline.Link ->
                    // A bare-URL autolink displays its own href as its text (indistinguishable
                    // from a manually-authored link by this point — both rendered to the same
                    // <a> HTML). If that URL happens to contain a `:shortcode:`-shaped
                    // substring, substituting would splice an emoji image into the middle of
                    // visible link text while href stays untouched, mismatching what's shown
                    // from where it points. Skip substitution specifically for that case.
                    listOf(
                        if (inlinePlainText(inline.children) == inline.href) inline
                        else inline.copy(children = inline.children.substituteEmoji(emoji)),
                    )
                // Inline.Code deliberately untouched: `:smile:` in code stays literal.
                else -> listOf(inline)
            }
        }

    /** Splits [text] around known `:shortcode:` tokens, emitting their emoji images. */
    private fun splitShortcodes(text: String, emoji: Map<String, Inline.Image>): List<Inline> {
        val result = mutableListOf<Inline>()
        var cursor = 0
        SHORTCODE.findAll(text).forEach { match ->
            val image = emoji[match.value] ?: return@forEach
            if (match.range.first > cursor) {
                result += Inline.Text(text.substring(cursor, match.range.first))
            }
            result += image
            cursor = match.range.last + 1
        }
        if (result.isEmpty()) return listOf(Inline.Text(text))
        if (cursor < text.length) result += Inline.Text(text.substring(cursor))
        return result
    }
}

/**
 * Parses this post's content for display, preferring the Markdown source over the
 * server-rendered HTML (see [MarkdownPostParser] for why). Falls back to [Post.bodyHtml]
 * when the source is absent (e.g. an API response that omits `body`).
 */
fun Post.parseBodyDocument(): PostDocument =
    if (body.isNotBlank()) MarkdownPostParser.parse(body, renderedHtml = bodyHtml)
    else HtmlPostParser.parse(bodyHtml)

/**
 * Parses this topic's author-written summary for display. Unlike post bodies, the HTML
 * rendering is preferred here: Ravelry's `summary` source has been observed with its
 * trailing syntax dropped (a closing `**` that never arrives), and `summary_html` shows
 * how the website resolves that (emphasis auto-closed). The Markdown source is only a
 * fallback for responses that omit the rendering.
 */
fun FeedItem.parseSummaryDocument(): PostDocument =
    if (bodySummaryHtml.isNotBlank()) HtmlPostParser.parse(bodySummaryHtml)
    else MarkdownPostParser.parse(bodySummary)
