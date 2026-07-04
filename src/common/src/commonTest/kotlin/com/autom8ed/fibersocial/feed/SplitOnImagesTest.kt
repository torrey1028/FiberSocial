package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.feed.html.Inline
import com.autom8ed.fibersocial.feed.html.InlineStyle
import kotlin.test.Test
import kotlin.test.assertEquals

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
