package com.myhobbyislearning.fibersocial.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountDeletionTest {

    @Test
    fun `deletion url points at the signed-in user's profile editor`() {
        assertEquals(
            "https://www.ravelry.com/people/torrey1028/edit",
            ravelryAccountDeletionUrl("torrey1028"),
        )
    }

    @Test
    fun `deletion url falls back to the handle-less editor before current_user resolves`() {
        assertEquals("https://www.ravelry.com/people/edit", ravelryAccountDeletionUrl(null))
    }

    @Test
    fun `a blank or whitespace handle takes the same fallback as null`() {
        assertEquals("https://www.ravelry.com/people/edit", ravelryAccountDeletionUrl(""))
        assertEquals("https://www.ravelry.com/people/edit", ravelryAccountDeletionUrl("   "))
    }

    @Test
    fun `surrounding whitespace is trimmed rather than escaped into the path`() {
        assertEquals(
            "https://www.ravelry.com/people/torrey1028/edit",
            ravelryAccountDeletionUrl("  torrey1028  "),
        )
    }

    @Test
    fun `a handle carrying path syntax is escaped instead of redirecting the URL`() {
        // The guard that matters: an unescaped "../.." would walk the URL somewhere else
        // entirely, and a bare slash would silently address a different Ravelry route.
        val url = ravelryAccountDeletionUrl("evil/../../account")
        assertTrue(url.startsWith("https://www.ravelry.com/people/"), url)
        assertTrue(url.endsWith("/edit"), url)
        val segment = url.removePrefix("https://www.ravelry.com/people/").removeSuffix("/edit")
        assertTrue('/' !in segment, "handle leaked a path separator: $segment")
    }

    @Test
    fun `handles stay on ravelry's own host`() {
        // Whatever the handle, the link must not become a hand-off to somewhere else —
        // this URL is opened in the user's real browser, already-authenticated.
        for (handle in listOf("torrey1028", "a b", "user@example.com", "..", "%2e%2e", null)) {
            assertTrue(
                ravelryAccountDeletionUrl(handle).startsWith("https://www.ravelry.com/people/"),
                "handle $handle produced ${ravelryAccountDeletionUrl(handle)}",
            )
        }
    }
}
