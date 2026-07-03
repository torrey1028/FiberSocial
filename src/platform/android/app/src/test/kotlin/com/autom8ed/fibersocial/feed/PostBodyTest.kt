package com.autom8ed.fibersocial.feed

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.autom8ed.fibersocial.feed.html.Inline
import com.autom8ed.fibersocial.feed.html.InlineStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val LINK = Color.Blue
private val CODE_BG = Color.Gray

class ResolveRavelryHrefTest {
    @Test
    fun `absolute urls pass through`() {
        assertEquals("https://example.com/x", resolveRavelryHref("https://example.com/x"))
    }

    @Test
    fun `site-relative paths get the ravelry origin`() {
        assertEquals("https://www.ravelry.com/patterns/library", resolveRavelryHref("/patterns/library"))
    }

    @Test
    fun `fragment-only footnote refs resolve to nothing`() {
        assertNull(resolveRavelryHref("#fn1"))
        assertNull(resolveRavelryHref(""))
    }
}

class BuildInlineTextTest {
    @Test
    fun `plain text and hard breaks concatenate`() {
        val text = buildInlineText(
            listOf(Inline.Text("a"), Inline.HardBreak, Inline.Text("b")),
            LINK, CODE_BG,
        )
        assertEquals("a\nb", text.text)
    }

    @Test
    fun `bold spans cover their children`() {
        val text = buildInlineText(
            listOf(Inline.Text("a "), Inline.Styled(InlineStyle.BOLD, listOf(Inline.Text("bc")))),
            LINK, CODE_BG,
        )
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("bc", text.text.substring(bold.start, bold.end))
    }

    @Test
    fun `nested styles produce overlapping spans`() {
        val text = buildInlineText(
            listOf(
                Inline.Styled(
                    InlineStyle.BOLD,
                    listOf(Inline.Text("b"), Inline.Styled(InlineStyle.ITALIC, listOf(Inline.Text("i")))),
                ),
            ),
            LINK, CODE_BG,
        )
        assertEquals("bi", text.text)
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 && it.end == 2 })
        assertTrue(text.spanStyles.any { it.item.fontStyle == FontStyle.Italic && it.start == 1 && it.end == 2 })
    }

    @Test
    fun `inline code renders monospace with background`() {
        val text = buildInlineText(listOf(Inline.Code("tag_names")), LINK, CODE_BG)
        val span = text.spanStyles.single()
        assertEquals(FontFamily.Monospace, span.item.fontFamily)
        assertEquals(CODE_BG, span.item.background)
    }

    @Test
    fun `links carry a resolved url annotation and link styling`() {
        val text = buildInlineText(
            listOf(Inline.Link("/groups/ravelry-api", listOf(Inline.Text("the group")))),
            LINK, CODE_BG,
        )
        val annotation = text.getStringAnnotations(URL_ANNOTATION, 0, text.length).single()
        assertEquals("https://www.ravelry.com/groups/ravelry-api", annotation.item)
        assertEquals("the group", text.text.substring(annotation.start, annotation.end))
        val style = text.spanStyles.single()
        assertEquals(LINK, style.item.color)
        assertEquals(TextDecoration.Underline, style.item.textDecoration)
    }

    @Test
    fun `footnote fragment links are styled but not clickable`() {
        val text = buildInlineText(
            listOf(Inline.Link("#fn1", listOf(Inline.Text("1")))),
            LINK, CODE_BG,
        )
        assertTrue(text.getStringAnnotations(URL_ANNOTATION, 0, text.length).isEmpty())
        assertEquals("1", text.text)
    }

    @Test
    fun `strikethrough maps to line-through decoration`() {
        val text = buildInlineText(
            listOf(Inline.Styled(InlineStyle.STRIKETHROUGH, listOf(Inline.Text("gone")))),
            LINK, CODE_BG,
        )
        assertEquals(TextDecoration.LineThrough, text.spanStyles.single().item.textDecoration)
    }
}

class SplitOnImagesTest {
    private val image = Inline.Image(url = "https://images.example/a.jpg", alt = "a")

    @Test
    fun `content without images is a single text run`() {
        val segments = splitOnImages(listOf(Inline.Text("hello")))
        assertEquals(listOf<ParagraphSegment>(ParagraphSegment.TextRun(listOf(Inline.Text("hello")))), segments)
    }

    @Test
    fun `images split the surrounding text into separate runs`() {
        val segments = splitOnImages(listOf(Inline.Text("before"), image, Inline.Text("after")))
        assertEquals(
            listOf(
                ParagraphSegment.TextRun(listOf(Inline.Text("before"))),
                ParagraphSegment.Photo(image),
                ParagraphSegment.TextRun(listOf(Inline.Text("after"))),
            ),
            segments,
        )
    }

    @Test
    fun `image-only paragraph yields just the photo`() {
        assertEquals(listOf<ParagraphSegment>(ParagraphSegment.Photo(image)), splitOnImages(listOf(image)))
    }
}

class HeadingStyleTest {
    private val typography = Typography()

    @Test
    fun `levels map to shrinking type roles`() {
        assertEquals(typography.headlineSmall.fontSize, headingStyle(1, typography).fontSize)
        assertEquals(typography.titleLarge.fontSize, headingStyle(2, typography).fontSize)
        assertEquals(typography.titleMedium.fontSize, headingStyle(3, typography).fontSize)
        assertEquals(typography.titleSmall.fontSize, headingStyle(6, typography).fontSize)
    }

    @Test
    fun `headings are always bold`() {
        (1..6).forEach { level ->
            assertEquals(FontWeight.Bold, headingStyle(level, typography).fontWeight)
        }
    }
}
