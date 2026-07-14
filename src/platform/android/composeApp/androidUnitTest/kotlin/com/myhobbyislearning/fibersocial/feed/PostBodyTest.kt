package com.myhobbyislearning.fibersocial.feed

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.myhobbyislearning.fibersocial.feed.html.Inline
import com.myhobbyislearning.fibersocial.feed.html.InlineStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val LINK = Color.Blue
private val CODE_BG = Color.Gray

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

    @Test
    fun `inline emoji are appended as inline-content placeholders carrying their alt text`() {
        val emoji = Inline.Image(url = "https://images.example/smile.gif", alt = ":)", cssClass = "emo")
        val text = buildInlineText(listOf(Inline.Text("hi "), emoji), LINK, CODE_BG)
        // appendInlineContent renders the alt text until the real inline content is laid
        // out, so it shows up in the built string in place of the image.
        assertEquals("hi :)", text.text)
    }

    @Test
    fun `non-emoji images stay dropped, lifted out at the paragraph level instead`() {
        val photo = Inline.Image(url = "https://images.example/photo.jpg", alt = "a photo")
        val text = buildInlineText(listOf(Inline.Text("a"), photo, Inline.Text("b")), LINK, CODE_BG)
        assertEquals("ab", text.text)
    }
}

class SplitOnImagesTest {
    private val image = Inline.Image(url = "https://images.example/a.jpg", alt = "a")
    private val emoji = Inline.Image(url = "https://images.example/smile.gif", alt = ":)", cssClass = "emo")

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

    @Test
    fun `inline emoji stay merged into the surrounding text run instead of becoming a photo`() {
        val segments = splitOnImages(listOf(Inline.Text("before "), emoji, Inline.Text(" after")))
        assertEquals(
            listOf(ParagraphSegment.TextRun(listOf(Inline.Text("before "), emoji, Inline.Text(" after")))),
            segments,
        )
    }

    @Test
    fun `size-flagged inline emoji also stay merged into the text run`() {
        val small = Inline.Image(url = "https://images.example/icon.gif", alt = "", width = 16, height = 16)
        assertEquals(listOf<ParagraphSegment>(ParagraphSegment.TextRun(listOf(small))), splitOnImages(listOf(small)))
    }

    @Test
    fun `a mix of inline emoji and a full photo splits only around the photo`() {
        val segments = splitOnImages(listOf(emoji, Inline.Text(" text "), image, emoji))
        assertEquals(
            listOf(
                ParagraphSegment.TextRun(listOf(emoji, Inline.Text(" text "))),
                ParagraphSegment.Photo(image),
                ParagraphSegment.TextRun(listOf(emoji)),
            ),
            segments,
        )
    }

    @Test
    fun `a photo wrapped in a link is lifted out carrying the link target`() {
        // Ravelry wraps every post photo in a link to its project page (issue #102).
        val link = Inline.Link(href = "/projects/u/shawl", children = listOf(image))
        assertEquals(
            listOf<ParagraphSegment>(ParagraphSegment.Photo(image, linkHref = "/projects/u/shawl")),
            splitOnImages(listOf(link)),
        )
    }

    @Test
    fun `text sharing a link with a photo stays a linked text run`() {
        val link = Inline.Link(href = "/p", children = listOf(Inline.Text("caption "), image))
        assertEquals(
            listOf(
                ParagraphSegment.TextRun(listOf(Inline.Link("/p", listOf(Inline.Text("caption "))))),
                ParagraphSegment.Photo(image, linkHref = "/p"),
            ),
            splitOnImages(listOf(link)),
        )
    }

    @Test
    fun `an emoji inside a link stays in the linked text run`() {
        val link = Inline.Link(href = "/p", children = listOf(Inline.Text("hi "), emoji))
        assertEquals(
            listOf<ParagraphSegment>(ParagraphSegment.TextRun(listOf(link))),
            splitOnImages(listOf(link)),
        )
    }

    @Test
    fun `a link with two photos lifts both with the same target`() {
        val second = Inline.Image(url = "https://images.example/b.jpg", alt = "b")
        val link = Inline.Link(href = "/p", children = listOf(image, second))
        assertEquals(
            listOf<ParagraphSegment>(
                ParagraphSegment.Photo(image, linkHref = "/p"),
                ParagraphSegment.Photo(second, linkHref = "/p"),
            ),
            splitOnImages(listOf(link)),
        )
    }
}

class CollectInlineEmojiTest {
    private val emoji = Inline.Image(url = "https://images.example/smile.gif", alt = ":)", cssClass = "emo")
    private val photo = Inline.Image(url = "https://images.example/photo.jpg", alt = "a photo")

    @Test
    fun `no emoji yields an empty list`() {
        assertEquals(emptyList(), collectInlineEmoji(listOf(Inline.Text("hi"), photo)))
    }

    @Test
    fun `top-level emoji are collected in order`() {
        val other = Inline.Image(url = "https://images.example/smile2.gif", alt = ":D", cssClass = "emo")
        assertEquals(listOf(emoji, other), collectInlineEmoji(listOf(emoji, Inline.Text(" "), other)))
    }

    @Test
    fun `emoji nested inside styled spans and links are collected`() {
        val content = listOf(
            Inline.Text("hi "),
            Inline.Styled(InlineStyle.BOLD, listOf(emoji)),
            photo,
            Inline.Link("https://example.com", listOf(emoji)),
        )
        assertEquals(listOf(emoji, emoji), collectInlineEmoji(content))
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
