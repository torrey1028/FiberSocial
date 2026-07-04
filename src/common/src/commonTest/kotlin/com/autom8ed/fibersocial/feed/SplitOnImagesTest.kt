package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.feed.html.Inline
import kotlin.test.Test
import kotlin.test.assertEquals

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
