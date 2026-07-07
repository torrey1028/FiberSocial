package com.autom8ed.fibersocial.feed.html

import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.RavelryUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Markdown samples are drawn from live `posts.json` captures for issue #102 — Ravelry
 * spells post photos as reference-style linked images with site-relative URLs, e.g.
 * `[![Firewood Shawl](/attached/courtneyshannon/23807567)](http://www.ravelry.com/...)`.
 */
class MarkdownPostParserTest {

    private fun singleParagraph(markdown: String, renderedHtml: String = ""): List<Inline> {
        val block = MarkdownPostParser.parse(markdown, renderedHtml).blocks.single()
        return assertIs<PostBlock.Paragraph>(block).content
    }

    @Test
    fun `plain paragraphs with emphasis parse to styled inlines`() {
        val content = singleParagraph("Hello **bold** world")
        val styled = content.filterIsInstance<Inline.Styled>().single()
        assertEquals(InlineStyle.BOLD, styled.style)
        assertEquals(listOf<Inline>(Inline.Text("bold")), styled.children)
    }

    @Test
    fun `crlf line endings parse like lf`() {
        val doc = MarkdownPostParser.parse("first\r\n\r\nsecond")
        assertEquals(2, doc.blocks.size)
    }

    @Test
    fun `linked reference image parses with the ravelry origin resolved`() {
        // The exact shape of the captured opening post of the issue #102 topic.
        val markdown = "[![Firewood Shawl](/attached/courtneyshannon/23807567)]" +
            "(http://www.ravelry.com/projects/courtneyshannon/no3)"
        val link = singleParagraph(markdown).filterIsInstance<Inline.Link>().single()
        val image = link.children.filterIsInstance<Inline.Image>().single()
        assertEquals("https://www.ravelry.com/attached/courtneyshannon/23807567", image.url)
        assertEquals("Firewood Shawl", image.alt)
    }

    @Test
    fun `image dropped from the server rendering still parses from the source`() {
        // Regression for issue #102: Ravelry's body_html omitted this image entirely.
        val markdown = "But here's a fully finished Gradient:\n\n" +
            "[![Gradient](/attached/critterknits/23064226)](http://www.ravelry.com/projects/critterknits/gradient)"
        val doc = MarkdownPostParser.parse(
            markdown,
            renderedHtml = "<p>But here&#8217;s a fully finished Gradient:</p>",
        )
        val images = doc.blocks.filterIsInstance<PostBlock.Paragraph>()
            .flatMap { it.content }
            .filterIsInstance<Inline.Link>()
            .flatMap { it.children }
            .filterIsInstance<Inline.Image>()
        assertEquals("https://www.ravelry.com/attached/critterknits/23064226", images.single().url)
    }

    // The exact emo markup Ravelry renders, from a live capture.
    private val emoHtml = "<p>Hi! <img alt=\"purple_heart\" title=\":purple_heart:\" class=\"emo\" " +
        "src=\"https://style-cdn.ravelrycache.com/images/twemoji/1f49c.png\" " +
        "srcset=\"https://style-cdn.ravelrycache.com/images/twemoji/svg/1f49c.svg 1.5x\" /></p>"

    @Test
    fun `emoji shortcodes substitute the twemoji image ravelry rendered`() {
        val content = singleParagraph("Love this. :purple_heart:", renderedHtml = emoHtml)
        val image = content.filterIsInstance<Inline.Image>().single()
        assertEquals("https://style-cdn.ravelrycache.com/images/twemoji/1f49c.png", image.url)
        assertTrue(image.isInlineEmoji)
        assertEquals(Inline.Text("Love this. "), content.first())
    }

    @Test
    fun `emoji shortcode falls back to the alt attribute when title is absent`() {
        val html = "<p><img alt=\"purple_heart\" class=\"emo\" " +
            "src=\"https://style-cdn.ravelrycache.com/images/twemoji/1f49c.png\" /></p>"
        val content = singleParagraph(":purple_heart:", renderedHtml = html)
        assertTrue(content.filterIsInstance<Inline.Image>().single().isInlineEmoji)
    }

    @Test
    fun `shortcodes ravelry did not render stay literal text`() {
        val content = singleParagraph("Love this. :not_an_emoji:", renderedHtml = emoHtml)
        assertEquals(listOf<Inline>(Inline.Text("Love this. :not_an_emoji:")), content)
    }

    @Test
    fun `colon-delimited plain text never becomes an image`() {
        val content = singleParagraph("Meet 10:30-11:00 :purple_heart:", renderedHtml = emoHtml)
        assertEquals(Inline.Text("Meet 10:30-11:00 "), content.first())
        assertEquals(1, content.filterIsInstance<Inline.Image>().size)
    }

    @Test
    fun `shortcodes inside inline code stay literal`() {
        val content = singleParagraph("type `:purple_heart:` to react", renderedHtml = emoHtml)
        assertEquals(":purple_heart:", content.filterIsInstance<Inline.Code>().single().text)
        assertEquals(0, content.filterIsInstance<Inline.Image>().size)
    }

    @Test
    fun `emoji substitute across headings lists quotes and tables but not code blocks`() {
        val markdown = "# hey :purple_heart:\n\n- item :purple_heart:\n\n1. one :purple_heart:\n\n" +
            "> quote :purple_heart:\n\n---\n\n| a |\n| --- |\n| cell :purple_heart: |\n\n" +
            "```\ncode :purple_heart:\n```"
        val doc = MarkdownPostParser.parse(markdown, renderedHtml = emoHtml)
        assertEquals(5, countEmoji(doc.blocks))
        assertTrue(doc.blocks.filterIsInstance<PostBlock.CodeBlock>().single().code.contains(":purple_heart:"))
    }

    private fun countEmoji(blocks: List<PostBlock>): Int = blocks.sumOf { block ->
        when (block) {
            is PostBlock.Paragraph -> block.content.count { it is Inline.Image }
            is PostBlock.Heading -> block.content.count { it is Inline.Image }
            is PostBlock.BulletList -> block.items.sumOf { countEmoji(it) }
            is PostBlock.OrderedList -> block.items.sumOf { countEmoji(it) }
            is PostBlock.Quote -> countEmoji(block.blocks)
            is PostBlock.Table -> (listOf(block.headerRow) + block.rows).sumOf { row ->
                row.sumOf { cell -> cell.content.count { it is Inline.Image } }
            }
            else -> 0
        }
    }

    @Test
    fun `a rendering with no emo images leaves shortcodes untouched`() {
        val content = singleParagraph(":purple_heart:", renderedHtml = "<p>plain rendering</p>")
        assertEquals(listOf<Inline>(Inline.Text(":purple_heart:")), content)
    }

    @Test
    fun `emo images with no usable shortcode or src are skipped`() {
        val html = "<p><img alt=\"\" class=\"emo\" src=\"https://style-cdn.example/1.png\" />" +
            "<img title=\":smile:\" class=\"emo\" src=\"\" /></p>"
        val content = singleParagraph(":smile:", renderedHtml = html)
        assertEquals(listOf<Inline>(Inline.Text(":smile:")), content)
    }

    @Test
    fun `a non-shortcode title falls back to the alt-derived shortcode`() {
        val html = "<p><img alt=\"smile\" title=\"smiley face\" class=\"emo\" " +
            "src=\"https://style-cdn.example/s.png\" /></p>"
        val content = singleParagraph(":smile:", renderedHtml = html)
        assertEquals("https://style-cdn.example/s.png", assertIs<Inline.Image>(content.single()).url)
    }

    @Test
    fun `text after a trailing shortcode is kept`() {
        val content = singleParagraph(":purple_heart: rocks", renderedHtml = emoHtml)
        assertEquals(Inline.Text(" rocks"), content.last())
    }

    @Test
    fun `emoji substitute inside styled spans and links`() {
        val content = singleParagraph("**so :purple_heart:** [me :purple_heart:](https://x.example)", renderedHtml = emoHtml)
        val styled = content.filterIsInstance<Inline.Styled>().single()
        val link = content.filterIsInstance<Inline.Link>().single()
        assertEquals(1, styled.children.filterIsInstance<Inline.Image>().size)
        assertEquals(1, link.children.filterIsInstance<Inline.Image>().size)
    }

    @Test
    fun `a bare-url autolink is left untouched even if it contains a shortcode-shaped substring`() {
        // The link's displayed text IS its href (an autolink) — splicing an emoji image
        // into the middle of it would show something the href no longer matches.
        val url = "http://example.com/:purple_heart:/page"
        val link = singleParagraph(url, renderedHtml = emoHtml).filterIsInstance<Inline.Link>().single()
        assertEquals(listOf<Inline>(Inline.Text(url)), link.children)
        assertEquals(url, link.href)
    }

    @Test
    fun `plainText unwraps paired emphasis and links`() {
        assertEquals(
            "Please use this thread for patterns",
            MarkdownPostParser.plainText("**Please use** this thread for [patterns](https://x.example)"),
        )
    }

    @Test
    fun `plainText drops the dangling emphasis of a truncated summary`() {
        // Ravelry's raw summary field can lose its closing ** (issue #104).
        assertEquals(
            "Please use this thread. This is a NO CHAT thread!!",
            MarkdownPostParser.plainText("**Please use this thread. This is a NO CHAT thread!!"),
        )
    }

    @Test
    fun `plainText keeps a literal asterisk used as punctuation rather than emphasis`() {
        assertEquals("Rated C* and 3*4=12", MarkdownPostParser.plainText("Rated C* and 3*4=12"))
    }

    @Test
    fun `plainText drops images and keeps surrounding text`() {
        assertEquals(
            "Here's mine:",
            MarkdownPostParser.plainText("Here's mine:\n\n[![Alt](/attached/u/1)](https://www.ravelry.com/p)"),
        )
    }

    @Test
    fun `plainText flattens lists and quotes to their text`() {
        assertEquals(
            "first second third quoted",
            MarkdownPostParser.plainText("- first\n- second\n\n1. third\n\n> quoted"),
        )
    }

    @Test
    fun `plainText flattens headings and code blocks and tables and dividers`() {
        val markdown = "# Heading\n\n```\ncode()\n```\n\n---\n\n| a | b |\n| --- | --- |\n| c | d |"
        assertEquals("Heading code() a b c d", MarkdownPostParser.plainText(markdown))
    }

    @Test
    fun `plainText keeps inline code and turns hard breaks into spaces`() {
        assertEquals("use ktor now", MarkdownPostParser.plainText("use `ktor`  \nnow"))
    }

    @Test
    fun `plainText of empty input is empty`() {
        assertEquals("", MarkdownPostParser.plainText(""))
    }

    @Test
    fun `parseBodyDocument prefers the markdown source over body_html`() {
        val post = Post(id = 1L, body = "from **markdown**", bodyHtml = "<p>from html</p>")
        val paragraph = assertIs<PostBlock.Paragraph>(post.parseBodyDocument().blocks.single())
        assertEquals(Inline.Text("from "), paragraph.content.first())
    }

    @Test
    fun `parseBodyDocument falls back to body_html when the source is absent`() {
        val post = Post(id = 1L, bodyHtml = "<p>from <b>html</b></p>")
        val paragraph = assertIs<PostBlock.Paragraph>(post.parseBodyDocument().blocks.single())
        assertEquals(Inline.Text("from "), paragraph.content.first())
        assertEquals(1, paragraph.content.filterIsInstance<Inline.Styled>().size)
    }

    private fun feedItem(bodySummary: String, bodySummaryHtml: String) = FeedItem(
        id = 1L,
        groupId = 1L,
        groupName = "G",
        lastPostAt = null,
        author = RavelryUser(username = "u"),
        title = "T",
        bodySummary = bodySummary,
        bodySummaryHtml = bodySummaryHtml,
        postCount = 0,
    )

    @Test
    fun `parseSummaryDocument prefers ravelry's html rendering`() {
        // The rendering resolves the dangling ** the raw source can't express.
        val item = feedItem(
            bodySummary = "**Please use this thread",
            bodySummaryHtml = "<p><strong>Please use this thread</strong></p>",
        )
        val paragraph = assertIs<PostBlock.Paragraph>(item.parseSummaryDocument().blocks.single())
        val styled = paragraph.content.filterIsInstance<Inline.Styled>().single()
        assertEquals(InlineStyle.BOLD, styled.style)
        assertEquals(listOf<Inline>(Inline.Text("Please use this thread")), styled.children)
    }

    @Test
    fun `parseSummaryDocument falls back to the markdown source`() {
        val item = feedItem(bodySummary = "plain **bold** summary", bodySummaryHtml = "")
        val paragraph = assertIs<PostBlock.Paragraph>(item.parseSummaryDocument().blocks.single())
        assertEquals(1, paragraph.content.filterIsInstance<Inline.Styled>().size)
    }
}
