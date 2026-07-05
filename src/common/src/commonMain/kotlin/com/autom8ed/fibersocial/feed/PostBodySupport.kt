package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.feed.html.Inline

/**
 * A piece of a paragraph: either a run of text inlines (which may itself contain inline
 * emoji images) or a lifted-out full content photo.
 */
sealed interface ParagraphSegment {
    data class TextRun(val content: List<Inline>) : ParagraphSegment
    data class Photo(val image: Inline.Image) : ParagraphSegment
}

/**
 * Splits paragraph content around full content photos so they can render as full-width
 * blocks. Small inline emoji ([Inline.Image.isInlineEmoji]) stay merged into the
 * surrounding text run instead, so they render inline at text size.
 */
fun splitOnImages(content: List<Inline>): List<ParagraphSegment> {
    val segments = mutableListOf<ParagraphSegment>()
    val run = mutableListOf<Inline>()
    fun flushRun() {
        if (run.isNotEmpty()) {
            segments += ParagraphSegment.TextRun(run.toList())
            run.clear()
        }
    }
    content.forEach { inline ->
        if (inline is Inline.Image && !inline.isInlineEmoji) {
            flushRun()
            segments += ParagraphSegment.Photo(inline)
        } else {
            run += inline
        }
    }
    flushRun()
    return segments
}

/**
 * Collects the inline emoji images present in [content], including inside styled spans
 * and links, in document order. Used to build the `inlineContent` map `InlineText` passes
 * to the underlying `Text` so each placeholder appended by `appendInlines` has a matching
 * renderer.
 */
fun collectInlineEmoji(content: List<Inline>): List<Inline.Image> =
    content.flatMap { inline ->
        when (inline) {
            is Inline.Image -> if (inline.isInlineEmoji) listOf(inline) else emptyList()
            is Inline.Styled -> collectInlineEmoji(inline.children)
            is Inline.Link -> collectInlineEmoji(inline.children)
            else -> emptyList()
        }
    }

/** Schemes a tapped link may open. Hrefs are user-generated, so anything else is inert. */
private val ALLOWED_LINK_SCHEMES = setOf("http", "https", "mailto")

private val URL_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

/**
 * Resolves a post link target to something the device can safely open. Site-relative
 * paths get the Ravelry origin; fragment-only links (Markdown footnote refs) and targets
 * with schemes outside [ALLOWED_LINK_SCHEMES] (`javascript:`, `intent:`, …) resolve to
 * nothing and render as styled but inert text.
 */
fun resolveRavelryHref(href: String): String? {
    val scheme = URL_SCHEME.find(href)?.let { it.groupValues[1].lowercase() }
    return when {
        href.startsWith("#") -> null
        href.startsWith("/") -> "https://www.ravelry.com$href"
        scheme != null -> href.takeIf { scheme in ALLOWED_LINK_SCHEMES }
        else -> null
    }
}
