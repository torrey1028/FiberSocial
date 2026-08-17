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

    /**
     * With the handle unknown, the flow's own next pages must still be reachable.
     *
     * `/people/edit` is what the app opens when the signed-in handle hasn't resolved, and
     * Ravelry answers it with the user's OWN editor — whose URL carries a handle, as does
     * the `confirm_delete` link on it. Scoping the allowlist to the handle-less path alone
     * therefore blocks the page immediately after the one it just opened, and the flow
     * blanks one tap in.
     *
     * Reachable, not theoretical: `FeedScreen` renders Settings *before* its
     * `state is FeedState.Loading` gate, and `user` is null in that state — so a rotation
     * with Settings open (which re-fires the feed load, issue #406) followed by a tap on
     * Delete account opens this page with no handle.
     */
    @Test
    fun `with the handle unknown the flow's handle-carrying pages are still reachable`() {
        for (path in listOf(
            "/people/torrey1028/edit",
            "/people/torrey1028/edit/privacy",
            "/people/torrey1028/confirm_delete",
            "/people/torrey1028/delete",
        )) {
            assertTrue(
                isAllowedAccountDeletionNavigation("https://www.ravelry.com$path", null),
                "$path blocked with an unresolved handle — the flow blanks one tap in",
            )
        }
    }

    /**
     * The unknown-handle widening is shape-based, and the shape must stay narrow: it may
     * admit the editor and the two delete endpoints for *some* handle, and nothing else.
     * The 2.1(a) crash came through the web messages composer, so those pages staying
     * blocked is the property that actually matters here.
     */
    @Test
    fun `the unknown-handle widening does not open the rest of the site`() {
        for (path in listOf(
            "/people/torrey1028/messages",
            "/people/torrey1028",
            "/people/torrey1028/edit-evil",
            "/people/torrey1028/confirm_delete/extra",
            "/messages",
            "/discuss/the-testing-pool",
            "/people",
            "/people/",
            "/",
        )) {
            assertFalse(
                isAllowedAccountDeletionNavigation("https://www.ravelry.com$path", null),
                "$path slipped through the unknown-handle widening",
            )
        }
    }

    /**
     * The widening applies ONLY when the handle is unknown. With one in hand the list stays
     * pinned to that user, so a known-handle session can never wander onto another
     * account's editor or delete endpoint.
     */
    @Test
    fun `a known handle is not widened to other handles`() {
        assertFalse(allowed("https://www.ravelry.com/someone-else/edit"))
        assertFalse(allowed("https://www.ravelry.com/people/someone-else/edit"))
        assertFalse(allowed("https://www.ravelry.com/people/someone-else/confirm_delete"))
        assertFalse(allowed("https://www.ravelry.com/people/someone-else/delete"))
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
     * The allowlist is evaluated against the identity the page was OPENED for, not a live
     * read of the signed-in user (issue #406's lesson, applied here) — and that still
     * matters even though an unknown handle no longer blocks the flow.
     *
     * What capturing buys is SCOPE. With a handle in hand the list is pinned to that one
     * user; a live read that flipped to null mid-flow would silently drop to the wider
     * shape-based set instead. `FeedScreen` captures the handle alongside the URL and
     * saves it beside it for that reason.
     *
     * An earlier version of this test asserted the opposite behaviour — that a null handle
     * REFUSES the real editor page — treating the block as the safety property. That was
     * wrong: the block is what makes the flow blank one tap in, since every page after the
     * handle-less entry point carries a handle. The two tests above cover the corrected
     * behaviour; this one keeps the scoping half honest.
     */
    @Test
    fun `a captured handle keeps the allowlist pinned to that user`() {
        // Known handle: pinned. Its own pages yes, anyone else's no.
        assertTrue(allowed("https://www.ravelry.com/people/torrey1028/confirm_delete"))
        assertFalse(allowed("https://www.ravelry.com/people/someone-else/confirm_delete"))
        // Unknown handle: the same page is reachable, because it has to be — but that is
        // strictly the weaker state, which is why the handle is captured rather than read
        // live once it is known.
        assertTrue(
            isAllowedAccountDeletionNavigation(
                "https://www.ravelry.com/people/someone-else/confirm_delete",
                null,
            ),
        )
    }
}
