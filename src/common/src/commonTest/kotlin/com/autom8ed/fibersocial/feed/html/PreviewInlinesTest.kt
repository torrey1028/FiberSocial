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
                PostBlock.Divider,
                PostBlock.Table(
                    headerRow = listOf(TableCell(listOf(Inline.Text("size")))),
                    rows = listOf(listOf(TableCell(listOf(Inline.Text("US 7"))))),
                ),
            ),
        )
        assertEquals("Supplies wool needles gauge matters size US 7", plain(doc.previewInlines()))
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
    fun `empty and image-only documents flatten to nothing`() {
        assertEquals(emptyList(), PostDocument(emptyList()).previewInlines())
        val imageOnly = PostDocument(
            listOf(PostBlock.Paragraph(listOf(Inline.Image(url = "https://img/p.jpg", alt = "")))),
        )
        assertEquals(emptyList(), imageOnly.previewInlines())
    }
}
