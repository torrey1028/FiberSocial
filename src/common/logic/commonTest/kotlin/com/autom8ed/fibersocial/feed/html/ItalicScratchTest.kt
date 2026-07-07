package com.autom8ed.fibersocial.feed.html

import kotlin.test.Test

class ItalicScratchTest {
    private fun dump(label: String, doc: PostDocument) {
        println("=== $label ===")
        println(doc.blocks)
        println("preview: " + doc.previewInlines())
    }

    @Test
    fun scratch() {
        dump("star", MarkdownPostParser.parse("Test *italic* topic"))
        dump("underscore", MarkdownPostParser.parse("Test _italic_ topic"))
        dump("html em", HtmlPostParser.parse("<p>Test <em>italic</em> topic</p>"))
    }
}
