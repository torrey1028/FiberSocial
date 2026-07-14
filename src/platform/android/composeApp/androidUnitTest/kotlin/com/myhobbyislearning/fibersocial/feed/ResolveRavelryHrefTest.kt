package com.myhobbyislearning.fibersocial.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `unsafe schemes resolve to nothing`() {
        assertNull(resolveRavelryHref("javascript:alert(1)"))
        assertNull(resolveRavelryHref("intent:#Intent;package=evil;end"))
        assertNull(resolveRavelryHref("file:///etc/passwd"))
    }

    @Test
    fun `mailto and case-variant http schemes are allowed`() {
        assertEquals("mailto:someone@example.com", resolveRavelryHref("mailto:someone@example.com"))
        assertEquals("HTTPS://example.com", resolveRavelryHref("HTTPS://example.com"))
    }

    @Test
    fun `scheme-less non-rooted targets resolve to nothing`() {
        assertNull(resolveRavelryHref("patterns/library"))
    }
}
