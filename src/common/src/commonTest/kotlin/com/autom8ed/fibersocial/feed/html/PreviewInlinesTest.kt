package com.autom8ed.fibersocial.feed.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewInlinesTest {

    private fun plain(inlines: List<Inline>): String = buildString {
        fun walk(run: List<Inline>) {
            run.forEach { inline ->
                when (inline) {
                    is Inline.Text -> append(inline.text)
                    is Inline.Code -> append(inline.text)
                    is Inline.Styled -> walk(inline.children)
                    is Inline.Link -> walk(inline.children)
                    is Inline.Image -> append("[img]")
                    Inline.HardBreak -> append("<br>")
                }
            }
        }
        walk(inlines)
    }

    @Test
    fun `keeps emphasis structure instead of stripping it`() {
        val doc = MarkdownPostParser.parse("Cast on **all** the stitches")
        val inlines = doc.previewInlines()
        assertEquals("Cast on all the stitches", plain(inlines))
        // The bold run survives as structure the renderer can style.
        assertTrue(
            inlines.any { it is Inline.Styled && it.style == InlineStyle.BOLD },
            "expected a BOLD inline in $inlines",
        )
    }

    @Test
    fun `keeps link text as a link`() {
        val doc = MarkdownPostParser.parse("see [this pattern](https://example.com/p)")
        val inlines = doc.previewInlines()
        assertEquals("see this pattern", plain(inlines))
        assertTrue(inlines.any { it is Inline.Link }, "expected a Link inline in $inlines")
    }

    @Test
    fun `joins blocks with a space and turns hard breaks into spaces`() {
        val doc = PostDocument(
            blocks = listOf(
                PostBlock.Paragraph(listOf(Inline.Text("First"), Inline.HardBreak, Inline.Text("line"))),
                PostBlock.Paragraph(listOf(Inline.Text("Second"))),
            ),
        )
        assertEquals("First line Second", plain(doc.previewInlines()))
    }

    @Test
    fun `drops photos but keeps inline emoji as their alt text`() {
        val doc = PostDocument(
            blocks = listOf(
                PostBlock.Paragraph(
                    listOf(
                        Inline.Text("Look "),
                        Inline.Image(url = "https://img/photo.jpg", alt = "my socks"),
                        Inline.Image(url = "https://img/smile.png", alt = "wink", cssClass = "emo"),
                    ),
                ),
            ),
        )
        assertEquals("Look wink", plain(doc.previewInlines()))
    }

    @Test
    fun `flattens lists quotes headings and tables into text`() {
        val doc = PostDocument(
            blocks = listOf(
                PostBlock.Heading(2, listOf(Inline.Text("Supplies"))),
                PostBlock.BulletList(
                    items = listOf(
                        listOf(PostBlock.Paragraph(listOf(Inline.Text("wool")))),
                        listOf(PostBlock.Paragraph(listOf(Inline.Text("needles")))),
                    ),
                ),
                PostBlock.Quote(listOf(PostBlock.Paragraph(listOf(Inline.Text("gauge matters"))))),
                PostBlock.CodeBlock("k2tog\n"),
                PostBlock.Divider,
                PostBlock.Table(
                    headerRow = listOf(TableCell(listOf(Inline.Text("size")))),
                    rows = listOf(listOf(TableCell(listOf(Inline.Text("US 7"))))),
                ),
            ),
        )
        assertEquals("Supplies wool needles gauge matters k2tog size US 7", plain(doc.previewInlines()))
    }

    @Test
    fun `stops collecting once the length budget is spent`() {
        val doc = PostDocument(
            blocks = List(50) { PostBlock.Paragraph(listOf(Inline.Text("x".repeat(20)))) },
        )
        val text = plain(doc.previewInlines(maxLength = 100))
        assertTrue(text.length in 100..130, "expected roughly the budget, got ${text.length}")
    }

    @Test
    fun `previewImageUrl finds the first photo including inside a link`() {
        // Ravelry wraps post photos in project links: [![alt](img)](project).
        val doc = HtmlPostParser.parse(
            """<p>look!</p><p><a href="https://www.ravelry.com/projects/y/socks">""" +
                """<img src="https://img.example/socks.jpg" alt="socks"/></a></p>""",
        )
        assertEquals("https://img.example/socks.jpg", doc.previewImageUrl())
    }

    @Test
    fun `previewImageUrl skips inline emoji and returns null without photos`() {
        val emojiOnly = PostDocument(
            listOf(
                PostBlock.Paragraph(
                    listOf(
                        Inline.Text("hi "),
                        Inline.Image(url = "https://img/smile.png", alt = "wink", cssClass = "emo"),
                    ),
                ),
            ),
        )
        assertEquals(null, emojiOnly.previewImageUrl())
        assertEquals(null, PostDocument(emptyList()).previewImageUrl())
    }

    @Test
    fun `previewImageUrl looks inside quotes lists and styled runs`() {
        val photo = Inline.Image(url = "https://img.example/found.jpg", alt = "")
        val inQuoteStyled = PostDocument(
            listOf(
                PostBlock.Heading(2, listOf(Inline.Text("no image here"))),
                PostBlock.Quote(
                    listOf(PostBlock.Paragraph(listOf(Inline.Styled(InlineStyle.BOLD, listOf(photo))))),
                ),
            ),
        )
        assertEquals("https://img.example/found.jpg", inQuoteStyled.previewImageUrl())

        val inBulletList = PostDocument(
            listOf(
                PostBlock.BulletList(
                    items = listOf(
                        listOf(PostBlock.Paragraph(listOf(Inline.Text("text only")))),
                        listOf(PostBlock.Paragraph(listOf(photo))),
                    ),
                ),
            ),
        )
        assertEquals("https://img.example/found.jpg", inBulletList.previewImageUrl())

        val inOrderedList = PostDocument(
            listOf(PostBlock.OrderedList(items = listOf(listOf(PostBlock.Paragraph(listOf(photo)))))),
        )
        assertEquals("https://img.example/found.jpg", inOrderedList.previewImageUrl())

        // Code blocks, dividers and tables never contribute a thumbnail.
        val nonImageBlocks = PostDocument(
            listOf(
                PostBlock.CodeBlock("val x = 1"),
                PostBlock.Divider,
                PostBlock.Table(
                    headerRow = listOf(TableCell(listOf(photo))),
                    rows = emptyList(),
                ),
            ),
        )
        assertEquals(null, nonImageBlocks.previewImageUrl())
    }

    @Test
    fun `styled or linked runs that reduce to only photos are dropped`() {
        val photo = Inline.Image(url = "https://img.example/p.jpg", alt = "")
        val doc = PostDocument(
            listOf(
                PostBlock.Paragraph(
                    listOf(
                        Inline.Styled(InlineStyle.BOLD, listOf(photo)),
                        Inline.Link("https://example.com", listOf(photo)),
                    ),
                ),
            ),
        )
        assertEquals(emptyList(), doc.previewInlines())
    }

    @Test
    fun `budget cuts stop inside styled and linked runs`() {
        val doc = PostDocument(
            listOf(
                PostBlock.Paragraph(
                    listOf(
                        Inline.Text("x".repeat(60)),
                        Inline.Styled(InlineStyle.BOLD, listOf(Inline.Text("y".repeat(60)))),
                        Inline.Link("https://example.com", listOf(Inline.Text("z".repeat(60)))),
                        Inline.Code("never reached"),
                        Inline.HardBreak,
                    ),
                ),
            ),
        )
        val text = plain(doc.previewInlines(maxLength = 80))
        assertTrue("z" !in text && "never" !in text, "expected the tail cut, got: $text")
        assertTrue(text.startsWith("x".repeat(60)), "head kept: $text")
    }

    @Test
    fun `empty and image-only documents flatten to nothing`() {
        assertEquals(emptyList(), PostDocument(emptyList()).previewInlines())
        val imageOnly = PostDocument(
            listOf(PostBlock.Paragraph(listOf(Inline.Image(url = "https://img/p.jpg", alt = "")))),
        )
        assertEquals(emptyList(), imageOnly.previewInlines())
    }
}
