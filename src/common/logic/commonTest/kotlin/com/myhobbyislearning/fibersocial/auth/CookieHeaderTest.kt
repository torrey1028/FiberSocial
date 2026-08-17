package com.myhobbyislearning.fibersocial.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class CookieHeaderTest {

    @Test
    fun `splits a multi-cookie header on semicolons`() {
        assertEquals(
            listOf("_ravelry_session" to "abc123", "remember" to "1"),
            parseCookieHeader("_ravelry_session=abc123; remember=1"),
        )
    }

    @Test
    fun `handles a single cookie`() {
        assertEquals(listOf("_ravelry_session" to "abc"), parseCookieHeader("_ravelry_session=abc"))
    }

    @Test
    fun `null blank and empty headers yield nothing`() {
        assertEquals(emptyList(), parseCookieHeader(null))
        assertEquals(emptyList(), parseCookieHeader(""))
        assertEquals(emptyList(), parseCookieHeader("   "))
        assertEquals(emptyList(), parseCookieHeader(";;"))
    }

    @Test
    fun `a value containing = is kept whole`() {
        // Base64 session payloads end in padding, so splitting on every '=' would truncate
        // the value and hand Ravelry a session that doesn't match anything.
        assertEquals(
            listOf("_ravelry_session" to "d29ybGQ=="),
            parseCookieHeader("_ravelry_session=d29ybGQ=="),
        )
    }

    @Test
    fun `a valueless segment is dropped rather than duplicated as its own value`() {
        // substringBefore('=') would have turned "flag" into ("flag" -> "flag").
        assertEquals(listOf("a" to "1"), parseCookieHeader("a=1; flag"))
    }

    @Test
    fun `a nameless segment is dropped`() {
        assertEquals(listOf("a" to "1"), parseCookieHeader("=orphan; a=1"))
    }

    @Test
    fun `an empty value is preserved`() {
        // Legitimate, and distinct from having no cookie at all.
        assertEquals(listOf("a" to ""), parseCookieHeader("a="))
    }

    @Test
    fun `names are trimmed but values are handed over untouched`() {
        // Values arrive in the encoding Ravelry set them with; normalising them is how a
        // session silently stops matching. Only the separator whitespace goes.
        assertEquals(listOf("a" to "1"), parseCookieHeader("  a =1"))
        assertEquals(listOf("a" to " 2"), parseCookieHeader("a= 2"))
    }
}
