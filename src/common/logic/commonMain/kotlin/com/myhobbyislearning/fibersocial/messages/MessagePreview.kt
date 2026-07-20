package com.myhobbyislearning.fibersocial.messages

import com.myhobbyislearning.fibersocial.feed.html.HtmlPostParser
import com.myhobbyislearning.fibersocial.feed.html.Inline
import com.myhobbyislearning.fibersocial.feed.html.previewInlines

/**
 * How many characters of body text a conversation row's preview asks for. The row renders
 * one ellipsized line, so this only bounds how much text is parsed and flattened — a few
 * dozen visible characters plus slack for whitespace collapsing.
 */
private const val MESSAGE_PREVIEW_MAX_LENGTH = 160

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Flattens a message body ([Message.contentHtml]) to the single line of plain text a
 * conversation row previews. Empty string when there is no body — see the note below on
 * why that is a routine, expected outcome and not an error.
 *
 * ## This REUSES the existing preview machinery rather than adding a flattener
 *
 * Bodies arrive as `content_html` and nothing else: `Message.content` is write-only,
 * verified live (see its KDoc), so HTML is the only body reads ever carry. That is already a solved problem here —
 * [HtmlPostParser] is the project's single HTML→`PostDocument` converter (adding a second
 * HTML path is an explicit project trap), and [previewInlines] is the existing walker that
 * collapses a document to a compact preview run.
 *
 * So this composes both and adds nothing: parse with [HtmlPostParser], flatten with
 * [previewInlines], then fold the resulting inline run down to a `String`.
 *
 * **This does NOT extend the two-walker sync invariant.** That invariant binds
 * [previewInlines] and `MarkdownPostParser.plainText` because both independently walk a
 * `PostDocument` and must agree on what each node collapses to. [flattenToText] below
 * walks no `PostDocument` — it consumes [previewInlines]'s already-flattened output, so it
 * inherits every collapse decision by construction and cannot drift from it. A new node
 * type still only needs handling in the two existing walkers.
 *
 * Whitespace runs are collapsed to single spaces because block joins, hard breaks and the
 * source HTML's own indentation all survive flattening as literal whitespace, and a
 * one-line row would otherwise render a ragged gap mid-sentence.
 *
 * @param contentHtml the message's `content_html`, or `null` when the body was not fetched
 *   — the list endpoint's `list` shape omits it, and whether `output_format=full` actually
 *   suppresses that is an unresolved doc ambiguity (see `RavelryApiClient.getMessages`).
 *   A row with no preview is a degraded row, never a broken one.
 */
fun messagePreviewText(contentHtml: String?): String {
    if (contentHtml.isNullOrBlank()) return ""
    val document = HtmlPostParser.parse(contentHtml)
    return flattenToText(document.previewInlines(MESSAGE_PREVIEW_MAX_LENGTH))
        .replace(WHITESPACE_RUN, " ")
        .trim()
}

/**
 * Folds a [previewInlines] run to bare text.
 *
 * The [Inline.HardBreak] and [Inline.Image] branches are unreachable in practice —
 * [previewInlines] has already turned hard breaks into spaces, inline emoji into their alt
 * text, and dropped content photos. They are handled anyway because [Inline] is `sealed`
 * and an exhaustive `when` is what makes a future variant a compile error here rather than
 * a silently swallowed one.
 */
private fun flattenToText(inlines: List<Inline>): String = buildString {
    fun walk(list: List<Inline>) {
        list.forEach { inline ->
            when (inline) {
                is Inline.Text -> append(inline.text)
                is Inline.Code -> append(inline.text)
                is Inline.Styled -> walk(inline.children)
                is Inline.Link -> walk(inline.children)
                is Inline.Image -> if (inline.isInlineEmoji) append(inline.alt)
                Inline.HardBreak -> append(' ')
            }
        }
    }
    walk(inlines)
}
