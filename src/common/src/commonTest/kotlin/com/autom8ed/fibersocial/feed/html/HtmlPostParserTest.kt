package com.autom8ed.fibersocial.feed.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Flattens inline content to plain text, ignoring styling — handy for content assertions. */
private fun List<Inline>.plainText(): String = joinToString("") { inline ->
    when (inline) {
        is Inline.Text -> inline.text
        is Inline.Styled -> inline.children.plainText()
        is Inline.Code -> inline.text
        is Inline.Link -> inline.children.plainText()
        is Inline.Image -> inline.alt
        is Inline.HardBreak -> "\n"
    }
}

class HtmlPostParserGoldenTest {
    private val doc = HtmlPostParser.parse(COMPLEX_POST_HTML)

    @Test
    fun `parses the full block sequence of the captured post`() {
        val kinds = doc.blocks.map { it::class.simpleName }
        assertEquals(
            listOf(
                "Paragraph", // hope this helps…
                "Paragraph", // Sets in the website UI…
                "BulletList", // tag_names / Collection
                "Paragraph", // Favourites (bookmarks)…
                "Heading", // what is returned
                "Table",
                "Paragraph", // Library and Queue accept tags…
                "Heading", // Examples in pseudo code
                "Paragraph", // Let me know which language…
                "CodeBlock",
            ),
            kinds,
        )
    }

    @Test
    fun `wrapper div is unwrapped, not rendered as a block`() {
        // The capture is wrapped in <div class="body forum_post_body">; its children
        // must surface as top-level blocks.
        assertIs<PostBlock.Paragraph>(doc.blocks.first())
    }

    @Test
    fun `br tags become hard breaks within the paragraph`() {
        val first = assertIs<PostBlock.Paragraph>(doc.blocks[0])
        assertEquals(2, first.content.count { it is Inline.HardBreak })
        val text = first.content.plainText()
        assertTrue(text.startsWith("hope this helps -- do you create a web app, or a mobile app ?"))
        assertTrue(text.contains("\nor what are you trying to do ?"))
    }

    @Test
    fun `whitespace runs collapse to single spaces`() {
        val first = assertIs<PostBlock.Paragraph>(doc.blocks[0])
        // Source HTML reads "on the website  in the top right corner" (two spaces).
        assertTrue(first.content.plainText().contains("on the website in the top right corner"))
    }

    @Test
    fun `tight list items hold inline code and text as implicit paragraphs`() {
        val list = assertIs<PostBlock.BulletList>(doc.blocks[2])
        assertEquals(2, list.items.size)
        val firstItem = assertIs<PostBlock.Paragraph>(list.items[0].single())
        assertEquals(Inline.Code("tag_names"), firstItem.content.first())
        assertTrue(firstItem.content.plainText().contains("(array of strings) on the entity itself"))
    }

    @Test
    fun `headings carry their level and text`() {
        val heading = assertIs<PostBlock.Heading>(doc.blocks[4])
        assertEquals(2, heading.level)
        assertEquals("what is returned", heading.content.plainText())
    }

    @Test
    fun `table header and body are fully extracted`() {
        val table = assertIs<PostBlock.Table>(doc.blocks[5])
        assertEquals(
            listOf("Area", "Per-item categorisation", "Top-level Sets / Collections"),
            table.headerRow.map { it.content.plainText() },
        )
        assertEquals(6, table.rows.size)
        assertTrue(table.rows.all { it.size == 3 })
        assertEquals("Projects", table.rows[0][0].content.plainText())
        assertEquals("Queue", table.rows[5][0].content.plainText())
    }

    @Test
    fun `table cells keep inline code and alignment`() {
        val table = assertIs<PostBlock.Table>(doc.blocks[5])
        val cell = table.rows[0][1] // "tag_names[] per project"
        assertEquals(Inline.Code("tag_names[]"), cell.content.first())
        assertEquals(CellAlignment.LEFT, cell.alignment)
    }

    @Test
    fun `code block preserves line breaks and decodes entities`() {
        val code = assertIs<PostBlock.CodeBlock>(doc.blocks[9])
        assertTrue(code.code.startsWith("# 1. PROJECTS — Collections returned natively"))
        assertTrue(code.code.lines().size > 30)
        // "&amp;page_size=100" in the source must decode to "&page_size=100".
        assertTrue(code.code.contains("&page_size=100"))
        assertTrue(code.code.endsWith("page_size=100"))
    }

    @Test
    fun `inline code decodes entities`() {
        val para = assertIs<PostBlock.Paragraph>(doc.blocks[6])
        assertTrue(para.content.contains(Inline.Code("query_type=tags&query=")))
    }
}

class HtmlPostParserInlineTest {
    private fun singleParagraph(html: String): List<Inline> {
        val block = HtmlPostParser.parse(html).blocks.single()
        return assertIs<PostBlock.Paragraph>(block).content
    }

    @Test
    fun `bold and italic wrap their children`() {
        val content = singleParagraph("<p>a <strong>b <em>c</em></strong></p>")
        assertEquals(
            listOf(
                Inline.Text("a "),
                Inline.Styled(
                    InlineStyle.BOLD,
                    listOf(Inline.Text("b "), Inline.Styled(InlineStyle.ITALIC, listOf(Inline.Text("c")))),
                ),
            ),
            content,
        )
    }

    @Test
    fun `b and i map to the same styles as strong and em`() {
        val content = singleParagraph("<p><b>x</b><i>y</i></p>")
        assertEquals(InlineStyle.BOLD, (content[0] as Inline.Styled).style)
        assertEquals(InlineStyle.ITALIC, (content[1] as Inline.Styled).style)
    }

    @Test
    fun `whitelisted html tags map to styles`() {
        val content = singleParagraph(
            "<p><del>gone</del><small>tiny</small><big>huge</big><sub>lo</sub><sup>hi</sup></p>"
        )
        assertEquals(
            listOf(
                InlineStyle.STRIKETHROUGH, InlineStyle.SMALL, InlineStyle.BIG,
                InlineStyle.SUBSCRIPT, InlineStyle.SUPERSCRIPT,
            ),
            content.map { (it as Inline.Styled).style },
        )
    }

    @Test
    fun `links keep href and styled children`() {
        val content = singleParagraph("""<p>see <a href="https://www.ravelry.com/patterns">the <em>patterns</em></a></p>""")
        val link = assertIs<Inline.Link>(content[1])
        assertEquals("https://www.ravelry.com/patterns", link.href)
        assertEquals("the patterns", link.children.plainText())
    }

    @Test
    fun `footnote references parse as superscript fragment links`() {
        val content = singleParagraph("""<p>claim<sup id="fnref1"><a href="#fn1">1</a></sup></p>""")
        val sup = assertIs<Inline.Styled>(content[1])
        assertEquals(InlineStyle.SUPERSCRIPT, sup.style)
        assertEquals("#fn1", assertIs<Inline.Link>(sup.children.single()).href)
    }

    @Test
    fun `images capture url and alt text`() {
        val content = singleParagraph("""<p><img src="https://images.example/yarn.jpg" alt="a skein"></p>""")
        assertEquals(listOf(Inline.Image(url = "https://images.example/yarn.jpg", alt = "a skein")), content)
    }

    @Test
    fun `images fall back to data-src when src is missing`() {
        val content = singleParagraph("""<p><img data-src="https://images.example/lazy.jpg" alt="lazy"></p>""")
        assertEquals("https://images.example/lazy.jpg", (content.single() as Inline.Image).url)
    }

    @Test
    fun `images fall back to data-src when src is blank`() {
        val content = singleParagraph(
            """<p><img src="" data-src="https://images.example/lazy.jpg" alt="lazy"></p>"""
        )
        assertEquals("https://images.example/lazy.jpg", (content.single() as Inline.Image).url)
    }

    @Test
    fun `images fall back to data-src when src is a data URI placeholder`() {
        val html = "<p><img src=\"data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==\"" +
            " data-src=\"https://images.example/lazy.jpg\" alt=\"lazy\"></p>"
        val content = singleParagraph(html)
        assertEquals("https://images.example/lazy.jpg", (content.single() as Inline.Image).url)
    }

    @Test
    fun `images fall back to the first srcset candidate when src and data-src are unusable`() {
        val content = singleParagraph(
            """<p><img srcset="https://images.example/small.jpg 480w, https://images.example/big.jpg 960w" alt="set"></p>"""
        )
        assertEquals("https://images.example/small.jpg", (content.single() as Inline.Image).url)
    }

    @Test
    fun `images skip a blank leading srcset candidate and use the next one`() {
        val content = singleParagraph(
            """<p><img srcset=" , https://images.example/small.jpg 480w" alt="set"></p>"""
        )
        assertEquals("https://images.example/small.jpg", (content.single() as Inline.Image).url)
    }

    @Test
    fun `images with no usable src, data-src, or srcset fall back to an empty url`() {
        val content = singleParagraph("""<p><img alt="none"></p>""")
        assertEquals(Inline.Image(url = "", alt = "none"), content.single())
    }

    @Test
    fun `protocol-relative image urls are normalized to https`() {
        val content = singleParagraph("""<p><img src="//images.example/yarn.jpg" alt="a skein"></p>""")
        assertEquals("https://images.example/yarn.jpg", (content.single() as Inline.Image).url)
    }

    @Test
    fun `protocol-relative data-src is normalized to https too`() {
        val content = singleParagraph(
            """<p><img data-src="//images.example/lazy.jpg" alt="lazy"></p>"""
        )
        assertEquals("https://images.example/lazy.jpg", (content.single() as Inline.Image).url)
    }

    @Test
    fun `unknown inline tags degrade to their content`() {
        val content = singleParagraph("<p>a <span class=\"future\">kept</span> b</p>")
        assertEquals("a kept b", content.plainText())
    }

    @Test
    fun `s and strike map to strikethrough like del`() {
        val content = singleParagraph("<p><s>a</s><strike>b</strike></p>")
        assertEquals(
            listOf(InlineStyle.STRIKETHROUGH, InlineStyle.STRIKETHROUGH),
            content.map { (it as Inline.Styled).style },
        )
    }

    @Test
    fun `html comments are ignored`() {
        val content = singleParagraph("<p>a<!-- hidden -->b</p>")
        assertEquals(listOf<Inline>(Inline.Text("a"), Inline.Text("b")), content)
    }

    @Test
    fun `leading whitespace before a styled element is dropped`() {
        val content = singleParagraph("<p> <em>lead</em></p>")
        assertEquals(
            listOf<Inline>(Inline.Styled(InlineStyle.ITALIC, listOf(Inline.Text("lead")))),
            content,
        )
    }

    @Test
    fun `trailing whitespace after a styled element is dropped`() {
        val content = singleParagraph("<p>a <em>b</em>   </p>")
        assertEquals(
            listOf(
                Inline.Text("a "),
                Inline.Styled(InlineStyle.ITALIC, listOf(Inline.Text("b"))),
            ),
            content,
        )
    }

    @Test
    fun `paragraph starting with a styled element keeps trailing text trimmed`() {
        val content = singleParagraph("<p><strong>x</strong> tail </p>")
        assertEquals(
            listOf(
                Inline.Styled(InlineStyle.BOLD, listOf(Inline.Text("x"))),
                Inline.Text(" tail"),
            ),
            content,
        )
    }
}

class HtmlPostParserBlockTest {
    @Test
    fun `ordered lists parse with items in order`() {
        val doc = HtmlPostParser.parse("<ol><li>first</li><li>second</li></ol>")
        val list = assertIs<PostBlock.OrderedList>(doc.blocks.single())
        assertEquals(
            listOf("first", "second"),
            list.items.map { (it.single() as PostBlock.Paragraph).content.plainText() },
        )
    }

    @Test
    fun `nested lists stay inside their parent item`() {
        val doc = HtmlPostParser.parse("<ul><li>outer<ul><li>inner</li></ul></li></ul>")
        val outer = assertIs<PostBlock.BulletList>(doc.blocks.single())
        val itemBlocks = outer.items.single()
        assertEquals("outer", (itemBlocks[0] as PostBlock.Paragraph).content.plainText())
        val inner = assertIs<PostBlock.BulletList>(itemBlocks[1])
        assertEquals("inner", (inner.items.single().single() as PostBlock.Paragraph).content.plainText())
    }

    @Test
    fun `blockquotes contain nested blocks`() {
        val doc = HtmlPostParser.parse("<blockquote><p>quoted <strong>text</strong></p><p>more</p></blockquote>")
        val quote = assertIs<PostBlock.Quote>(doc.blocks.single())
        assertEquals(2, quote.blocks.size)
        assertEquals("quoted text", (quote.blocks[0] as PostBlock.Paragraph).content.plainText())
    }

    @Test
    fun `hr becomes a divider`() {
        val doc = HtmlPostParser.parse("<p>above</p><hr><p>below</p>")
        assertEquals(PostBlock.Divider, doc.blocks[1])
    }

    @Test
    fun `table cell alignment styles are honored`() {
        val doc = HtmlPostParser.parse(
            """<table><tbody><tr>
               <td style="text-align: left;">l</td>
               <td style="text-align: center;">c</td>
               <td style="text-align: right;">r</td>
               <td>default</td>
               </tr></tbody></table>"""
        )
        val table = assertIs<PostBlock.Table>(doc.blocks.single())
        assertEquals(
            listOf(CellAlignment.LEFT, CellAlignment.CENTER, CellAlignment.RIGHT, CellAlignment.LEFT),
            table.rows.single().map { it.alignment },
        )
    }

    @Test
    fun `table cell alignment tolerates spacing and casing variants`() {
        val doc = HtmlPostParser.parse(
            """<table><tbody><tr>
               <td style="text-align:center;">no space</td>
               <td style="TEXT-ALIGN: Right">upper</td>
               </tr></tbody></table>"""
        )
        val table = assertIs<PostBlock.Table>(doc.blocks.single())
        assertEquals(
            listOf(CellAlignment.CENTER, CellAlignment.RIGHT),
            table.rows.single().map { it.alignment },
        )
    }

    @Test
    fun `thead cells may be td elements`() {
        val doc = HtmlPostParser.parse(
            "<table><thead><tr><td>H1</td><td>H2</td></tr></thead><tbody><tr><td>x</td><td>y</td></tr></tbody></table>"
        )
        val table = assertIs<PostBlock.Table>(doc.blocks.single())
        assertEquals(listOf("H1", "H2"), table.headerRow.map { it.content.plainText() })
        assertEquals(1, table.rows.size)
    }

    @Test
    fun `th row-header cells in the body are kept`() {
        val doc = HtmlPostParser.parse("<table><tbody><tr><th>name</th><td>value</td></tr></tbody></table>")
        val table = assertIs<PostBlock.Table>(doc.blocks.single())
        assertEquals(listOf("name", "value"), table.rows.single().map { it.content.plainText() })
    }

    @Test
    fun `empty body rows are dropped`() {
        val doc = HtmlPostParser.parse("<table><tbody><tr></tr><tr><td>only</td></tr></tbody></table>")
        val table = assertIs<PostBlock.Table>(doc.blocks.single())
        assertEquals(1, table.rows.size)
    }

    @Test
    fun `cell style without text-align defaults to left`() {
        val doc = HtmlPostParser.parse("""<table><tbody><tr><td style="color: red;">x</td></tr></tbody></table>""")
        val table = assertIs<PostBlock.Table>(doc.blocks.single())
        assertEquals(CellAlignment.LEFT, table.rows.single().single().alignment)
    }

    @Test
    fun `table without thead has an empty header row`() {
        val doc = HtmlPostParser.parse("<table><tbody><tr><td>only</td></tr></tbody></table>")
        val table = assertIs<PostBlock.Table>(doc.blocks.single())
        assertTrue(table.headerRow.isEmpty())
        assertEquals(1, table.rows.size)
    }

    @Test
    fun `pre without inner code still parses as a code block`() {
        val doc = HtmlPostParser.parse("<pre>raw\n  indented</pre>")
        assertEquals(PostBlock.CodeBlock("raw\n  indented"), doc.blocks.single())
    }

    @Test
    fun `unknown block containers unwrap to their nested blocks`() {
        val doc = HtmlPostParser.parse("<section><p>a</p><p>b</p></section>")
        assertEquals(2, doc.blocks.size)
        assertEquals("a", (doc.blocks[0] as PostBlock.Paragraph).content.plainText())
        assertEquals("b", (doc.blocks[1] as PostBlock.Paragraph).content.plainText())
    }

    @Test
    fun `unknown block container with inline content becomes a paragraph`() {
        val doc = HtmlPostParser.parse("""<figure><img src="https://images.example/a.jpg" alt="a"></figure>""")
        val para = assertIs<PostBlock.Paragraph>(doc.blocks.single())
        assertEquals(listOf<Inline>(Inline.Image(url = "https://images.example/a.jpg", alt = "a")), para.content)
    }

    @Test
    fun `bare inline content at top level becomes a paragraph`() {
        val doc = HtmlPostParser.parse("just <em>text</em> with no wrapper")
        val para = assertIs<PostBlock.Paragraph>(doc.blocks.single())
        assertEquals("just text with no wrapper", para.content.plainText())
    }

    @Test
    fun `empty and whitespace-only input yields no blocks`() {
        assertTrue(HtmlPostParser.parse("").blocks.isEmpty())
        assertTrue(HtmlPostParser.parse("  \n  ").blocks.isEmpty())
    }

    @Test
    fun `empty paragraphs are dropped`() {
        assertTrue(HtmlPostParser.parse("<p> </p>").blocks.isEmpty())
    }
}
