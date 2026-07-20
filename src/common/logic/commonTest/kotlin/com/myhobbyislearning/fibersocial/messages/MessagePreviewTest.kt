package com.myhobbyislearning.fibersocial.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [messagePreviewText] composes the existing `HtmlPostParser` + `previewInlines` machinery
 * rather than adding a third HTML walker, so these assert the COMPOSITION — that the
 * inline run really does fold down to the one line a conversation row shows, and that the
 * cases which would visibly break a row (markup leaking, a `null` body, a multi-paragraph
 * body running together without a space) are handled.
 */
class MessagePreviewTest {

    @Test
    fun `a plain paragraph previews as its text`() {
        assertEquals("Hello there", messagePreviewText("<p>Hello there</p>"))
    }

    @Test
    fun `a null body previews as empty`() {
        assertEquals("", messagePreviewText(null))
    }

    @Test
    fun `a blank body previews as empty`() {
        assertEquals("", messagePreviewText("   "))
    }

    /** Emphasis must read as words, never as leaked markup or tags. */
    @Test
    fun `styling is unwrapped to its text`() {
        val preview = messagePreviewText("<p>I <strong>love</strong> this <em>yarn</em></p>")
        assertEquals("I love this yarn", preview)
    }

    /** Links preview as their label — a bare URL would eat the whole line. */
    @Test
    fun `a link previews as its label rather than its href`() {
        val preview = messagePreviewText("""<p>See <a href="https://example.com/x">my project</a></p>""")
        assertEquals("See my project", preview)
        assertTrue(!preview.contains("example.com"))
    }

    /** Blocks join with a space so two paragraphs don't run into one another. */
    @Test
    fun `separate paragraphs are joined with a space`() {
        assertEquals("First Second", messagePreviewText("<p>First</p><p>Second</p>"))
    }

    /** A hard break is one line's worth of nothing on a single-line row. */
    @Test
    fun `hard breaks collapse to spaces`() {
        assertEquals("One Two", messagePreviewText("<p>One<br/>Two</p>"))
    }

    /**
     * Source HTML indentation and newlines survive flattening as literal whitespace; a
     * one-line row would otherwise show a ragged gap mid-sentence.
     */
    @Test
    fun `whitespace runs collapse to single spaces`() {
        assertEquals("Lots of space", messagePreviewText("<p>Lots\n\n   of    space</p>"))
    }

    /** Content photos are dropped by previewInlines; the row stays text-only. */
    @Test
    fun `a content photo contributes no text`() {
        val preview = messagePreviewText("""<p>Look <img src="https://example.com/big.jpg" alt="a sweater"/></p>""")
        assertEquals("Look", preview)
    }

    /** An inline emoji becomes its alt text rather than vanishing into "". */
    @Test
    fun `an inline emoji previews as its alt text`() {
        val preview = messagePreviewText(
            """<p>Nice <img class="emo" src="https://example.com/s.png" alt=":heart:"/></p>""",
        )
        assertEquals("Nice :heart:", preview)
    }

    /** A list still reads as words rather than collapsing to nothing. */
    @Test
    fun `list items contribute their text`() {
        assertEquals("wool silk", messagePreviewText("<ul><li>wool</li><li>silk</li></ul>"))
    }

    /**
     * The row renders one ellipsized line, so the helper only has to bound how much text is
     * parsed — not produce an exact width. This pins that a very long body is truncated
     * rather than handed over whole.
     */
    @Test
    fun `a very long body is clipped`() {
        val long = "word ".repeat(500)
        val preview = messagePreviewText("<p>$long</p>")
        assertTrue(preview.length <= 160, "expected a clipped preview but got ${preview.length} chars")
    }
}
