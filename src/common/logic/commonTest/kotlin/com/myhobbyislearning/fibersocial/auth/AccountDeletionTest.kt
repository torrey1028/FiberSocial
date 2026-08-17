package com.myhobbyislearning.fibersocial.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // --- Navigation allowlist for the in-app deletion page (issues #481, #425) ---

    private fun allowed(url: String) = isAllowedAccountDeletionNavigation(url, "torrey1028")

    @Test
    fun `the profile editor and its tab sub-paths are allowed`() {
        assertTrue(allowed("https://www.ravelry.com/people/torrey1028/edit"))
        assertTrue(allowed("https://www.ravelry.com/people/torrey1028/edit/privacy"))
        // Ravelry bounces here when the session is not valid, and its sign-in form POSTs
        // back to the same path — without it the flow dead-ends for a signed-out user.
        assertTrue(allowed("https://www.ravelry.com/account/login"))
    }

    @Test
    fun `the two steps behind ravelry's delete link are allowed`() {
        // Both observed on device 2026-08-13, from blocked-navigation log lines, and both
        // SIBLINGS of the editor rather than children — the prefix this list originally
        // shipped with covered neither, and each step blanked in turn. This is why the
        // list is only ever widened from a trace.
        assertTrue(allowed("https://www.ravelry.com/people/torrey1028/confirm_delete"))
        assertTrue(allowed("https://www.ravelry.com/people/torrey1028/delete"))
        // Where deletion lands afterwards — the only page that tells the user it worked.
        assertTrue(allowed("https://www.ravelry.com/account/closed"))
        assertTrue(
            isAllowedAccountDeletionNavigation(
                "https://www.ravelry.com/people/confirm_delete",
                null,
            ),
        )
        // Still scoped to the signed-in user: nobody else's account is deletable here.
        assertFalse(allowed("https://www.ravelry.com/people/someone-else/confirm_delete"))
        assertFalse(allowed("https://www.ravelry.com/people/someone-else/delete"))
    }

    @Test
    fun `the rest of the ravelry site is NOT allowed just because it is ravelry`() {
        // The 2.1(a) crash came from roaming Ravelry inside a web view we opened; the
        // reviewer never left ravelry.com, so "still on Ravelry" is not the guarantee.
        assertFalse(allowed("https://www.ravelry.com/"))
        assertFalse(allowed("https://www.ravelry.com/messages"))
        assertFalse(allowed("https://www.ravelry.com/discuss/the-testing-pool"))
        assertFalse(allowed("https://www.ravelry.com/people/someone-else/edit"))
        // Prefix-adjacent, but a different account's page.
        assertFalse(allowed("https://www.ravelry.com/people/torrey1028-evil/edit"))
        // Their profile, but not the editor.
        assertFalse(allowed("https://www.ravelry.com/people/torrey1028"))
    }

    @Test
    fun `off-site and lookalike hosts are not allowed`() {
        assertFalse(allowed("https://www.ravelry.com.evil.com/people/torrey1028/edit"))
        assertFalse(allowed("https://evil.com/people/torrey1028/edit"))
        assertFalse(allowed("http://www.ravelry.com/people/torrey1028/edit"))
        assertFalse(allowed("javascript:alert(1)"))
        assertFalse(allowed("https://www.ravelry.com/people/torrey1028/edit/..%2f..%2fmessages"))
    }

    @Test
    fun `the handle-less fallback allows its own editor and nothing wider`() {
        assertTrue(isAllowedAccountDeletionNavigation("https://www.ravelry.com/people/edit", null))
        assertTrue(isAllowedAccountDeletionNavigation("https://www.ravelry.com/people/edit/privacy", null))
        assertFalse(isAllowedAccountDeletionNavigation("https://www.ravelry.com/people", null))
        assertFalse(isAllowedAccountDeletionNavigation("https://www.ravelry.com/", null))
    }

    @Test
    fun `the url the app opens is itself allowed`() {
        // Pins the two against each other: if the URL builder ever changes shape, the
        // allowlist cannot silently stop covering the page the app actually opens.
        for (handle in listOf("torrey1028", null)) {
            assertTrue(
                isAllowedAccountDeletionNavigation(ravelryAccountDeletionUrl(handle), handle),
                "handle $handle",
            )
        }
    }

    /**
     * The same pinning, for handles that do NOT survive the path encoder untouched.
     *
     * `ravelrySafePath` rejects any `%` in a path — deliberately, so a prefix check can't
     * be walked through `..%2f`. A handle needing escapes would therefore build a URL the
     * allowlist refuses, and the web view would block its own first page: the deletion
     * flow would open to a cancelled navigation with no way forward. The builder now
     * falls back to the handle-less editor instead, which Ravelry redirects to the
     * signed-in user's own, so the two stay in agreement whatever the handle contains.
     */
    @Test
    fun `a handle the path encoder has to escape still opens a page the allowlist permits`() {
        for (handle in listOf("a b", "ün", "a/b", "a%b", "a#b", "a?b")) {
            val url = ravelryAccountDeletionUrl(handle)
            assertTrue('%' !in url, "handle $handle built an unopenable escaped URL: $url")
            assertTrue(
                isAllowedAccountDeletionNavigation(url, handle),
                "handle $handle built $url which its own allowlist rejects",
            )
        }
    }

    /**
     * The allowlist must be evaluated against the identity the page was OPENED for, not
     * against a live read of the signed-in user (issue #406's lesson, applied here).
     *
     * This is what makes that matter: asked about the real editor page with a null handle,
     * the allowlist resolves to the handle-less `/people/edit` and refuses it. The feed's
     * copy of the user IS momentarily null while the feed reloads — a rotation triggers
     * exactly that — and the deletion page survives rotation, so a caller reading the user
     * live would cancel the next navigation mid-flow: Ravelry's redirect, the login POST,
     * or the delete link itself. FeedScreen captures the handle alongside the URL for this
     * reason; if that ever regresses, this is the behaviour that makes it bite.
     */
    @Test
    fun `a null handle does not authorise the page a real handle opened`() {
        assertFalse(
            isAllowedAccountDeletionNavigation(
                "https://www.ravelry.com/people/torrey1028/edit",
                null,
            ),
        )
    }
}
