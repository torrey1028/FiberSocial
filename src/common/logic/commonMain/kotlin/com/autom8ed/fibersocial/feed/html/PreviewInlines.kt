package com.autom8ed.fibersocial.feed.html

/**
 * Flattens this document into a single inline run for a compact card preview
 * (issue #154): the card renders it as a styled [Inline] list instead of the old
 * plain-text stripping, so emphasis/links/code show as formatting rather than
 * leaking their markup.
 *
 * Preview-specific transforms:
 * - Blocks are joined with a single space; block kinds that make no sense at two
 *   lines tall (dividers) are dropped.
 * - Hard line breaks become spaces — a literal `\n` would eat one of the card's
 *   two lines for no information.
 * - Photos are dropped (the card stays text-only, issue #77); inline emoji become
 *   their alt text so a smiley doesn't vanish into "".
 * - Accumulation stops once [maxLength] characters of text have been collected —
 *   the card ellipsizes visually, this just avoids styling kilobytes nobody sees.
 */
fun PostDocument.previewInlines(maxLength: Int = 200): List<Inline> {
    val collector = PreviewCollector(maxLength)
    collector.addBlocks(blocks)
    return collector.result()
}

/**
 * The first content photo in this document, for the card's preview thumbnail
 * (issue #154 — the old plain-text preview silently dropped images). Inline emoji
 * don't count; links are looked into because Ravelry wraps post photos in links
 * (`[![…](img)](project)`).
 */
fun PostDocument.previewImageUrl(): String? {
    fun firstImage(content: List<Inline>): String? = content.firstNotNullOfOrNull { inline ->
        when (inline) {
            is Inline.Image -> inline.url.takeIf { !inline.isInlineEmoji }
            is Inline.Styled -> firstImage(inline.children)
            is Inline.Link -> firstImage(inline.children)
            else -> null
        }
    }

    fun firstImage(blocks: List<PostBlock>): String? = blocks.firstNotNullOfOrNull { block ->
        when (block) {
            is PostBlock.Paragraph -> firstImage(block.content)
            is PostBlock.Heading -> firstImage(block.content)
            is PostBlock.Quote -> firstImage(block.blocks)
            is PostBlock.BulletList -> block.items.firstNotNullOfOrNull { firstImage(it) }
            is PostBlock.OrderedList -> block.items.firstNotNullOfOrNull { firstImage(it) }
            is PostBlock.Table -> null
            is PostBlock.CodeBlock, PostBlock.Divider -> null
        }
    }

    return firstImage(blocks)
}

private class PreviewCollector(private val maxLength: Int) {
    private val out = mutableListOf<Inline>()
    private var length = 0

    fun addBlocks(blocks: List<PostBlock>) {
        blocks.forEach { block ->
            if (length >= maxLength) return
            when (block) {
                is PostBlock.Paragraph -> addRun(block.content)
                is PostBlock.Heading -> addRun(block.content)
                is PostBlock.Quote -> addBlocks(block.blocks)
                is PostBlock.BulletList -> block.items.forEach { addBlocks(it) }
                is PostBlock.OrderedList -> block.items.forEach { addBlocks(it) }
                is PostBlock.CodeBlock -> addRun(listOf(Inline.Code(block.code.trim())))
                is PostBlock.Table -> {
                    addRun(block.headerRow.flatMap { it.content })
                    block.rows.forEach { row -> addRun(row.flatMap { it.content }) }
                }
                PostBlock.Divider -> Unit
            }
        }
    }

    /** Adds one block's inline content, separated from the previous block by a space. */
    private fun addRun(content: List<Inline>) {
        val transformed = transform(content)
        if (transformed.isEmpty()) return
        // A separator is only ever needed before a non-empty run once something is
        // already collected — which is exactly `out.isNotEmpty()`.
        if (out.isNotEmpty() && length < maxLength) {
            out.add(Inline.Text(" "))
            length += 1
        }
        out.addAll(transformed)
    }

    private fun transform(content: List<Inline>): List<Inline> = content.mapNotNull { inline ->
        if (length >= maxLength) return@mapNotNull null
        when (inline) {
            is Inline.Text -> take(inline.text)?.let { if (it == inline.text) inline else Inline.Text(it) }
            is Inline.Code -> take(inline.text)?.let { if (it == inline.text) inline else Inline.Code(it) }
            is Inline.HardBreak -> take(" ")?.let { Inline.Text(it) }
            is Inline.Styled -> transform(inline.children)
                .takeIf { it.isNotEmpty() }?.let { inline.copy(children = it) }
            is Inline.Link -> transform(inline.children)
                .takeIf { it.isNotEmpty() }?.let { inline.copy(children = it) }
            is Inline.Image -> if (inline.isInlineEmoji) take(inline.alt)?.let { Inline.Text(it) } else null
        }
    }

    /**
     * Charges [text] against the remaining budget and returns the part that fit, or null
     * if nothing fits or it was blank. Clipping here (rather than only checking at node
     * boundaries) caps a single huge Text/Code node — a long plain-prose reply is one
     * such node, and would otherwise style the whole multi-KB body for a two-line card —
     * and dropping blanks skips an empty code block's stray span and a blank emoji alt.
     */
    private fun take(text: String): String? {
        if (length >= maxLength) return null
        val clipped = text.take(maxLength - length)
        length += clipped.length
        return clipped.ifEmpty { null }
    }

    fun result(): List<Inline> = out.toList()
}
