package com.myhobbyislearning.fibersocial.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginNavigationPolicyTest {

    // --- The observed auth flow (navigation trace, issue #425) is allowed ---

    @Test
    fun `allows the oauth2 auth entry point with its query`() {
        assertTrue(
            isAllowedLoginNavigation(
                "https://www.ravelry.com/oauth2/auth?response_type=code&client_id=abc&redirect_uri=fibersocial%3A%2F%2Fauth%2Fcallback",
            ),
        )
    }

    @Test
    fun `allows the account login form`() {
        assertTrue(isAllowedLoginNavigation("https://www.ravelry.com/account/login"))
    }

    @Test
    fun `allows the consent page with its uuid query`() {
        assertTrue(
            isAllowedLoginNavigation(
                "https://www.ravelry.com/consent?consent=37215976-e374-484e-b375-029999703893",
            ),
        )
    }

    @Test
    fun `allows the app redirect uri`() {
        assertTrue(isAllowedLoginNavigation("${RavelryAuthManager.REDIRECT_URI}?code=abc&state=xyz"))
    }

    @Test
    fun `allows the bare ravelry host for the same paths`() {
        assertTrue(isAllowedLoginNavigation("https://ravelry.com/account/login"))
    }

    @Test
    fun `allows about blank`() {
        assertTrue(isAllowedLoginNavigation("about:blank"))
    }

    // --- The login page's auth-adjacent detours stay usable in-app ---

    @Test
    fun `allows the forgot password page and its query variants`() {
        assertTrue(isAllowedLoginNavigation("https://www.ravelry.com/account/forgot"))
        assertTrue(isAllowedLoginNavigation("https://www.ravelry.com/account/forgot?forgot=username"))
    }

    @Test
    fun `allows the invitations sign-up flow and its sub-steps`() {
        assertTrue(isAllowedLoginNavigation("https://www.ravelry.com/invitations"))
        assertTrue(isAllowedLoginNavigation("https://www.ravelry.com/invitations/ask"))
    }

    // --- The rest of the Ravelry site is not ---

    @Test
    fun `blocks the ravelry home page`() {
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com/"))
    }

    @Test
    fun `blocks the web messages inbox Apple browsed to`() {
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com/messages"))
    }

    @Test
    fun `blocks patterns and other site sections`() {
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com/patterns"))
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com/account/settings"))
    }

    @Test
    fun `blocks the site links the detour pages render in their footer`() {
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com/about"))
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com/groups/ravelry-api"))
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com/discuss/search"))
    }

    @Test
    fun `blocks account login as a path prefix rather than an exact match`() {
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com/account/login/../../messages"))
    }

    // --- Parser quirks must not smuggle paths past the allowlist ---
    // Ktor's Url leaves dot-segments and %-escapes in encodedPath untouched, so the
    // policy must reject them itself rather than trust the WebView and Ravelry's
    // Rails router to agree on canonicalization (a Rack front end unescapes %2F
    // after routing, which would turn /oauth2/..%2fmessages into /messages).

    @Test
    fun `blocks dot-segment and encoded traversal under the oauth2 prefix`() {
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com/oauth2/../messages"))
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com/oauth2/..%2fmessages"))
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com/oauth2/%2e%2e/messages"))
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com/oauth2//messages"))
    }

    @Test
    fun `treats the host case-insensitively`() {
        // Hosts are case-insensitive (RFC 3986); a server redirect carrying an
        // uppercase host must not read as off-flow — that would burn a restart.
        assertTrue(isAllowedLoginNavigation("https://WWW.Ravelry.com/account/login"))
    }

    @Test
    fun `allows the exact url the auth manager actually builds`() {
        // Ties the allowlist to the real authorize URL so the two can't silently
        // drift apart — an off-allowlist entry point would restart-loop straight
        // into FAIL_LOGIN on both platforms with every unit test still green.
        assertTrue(isAllowedLoginNavigation(RavelryAuthManager().buildAuthUrl("client-id")))
    }

    // --- Non-Ravelry and lookalike destinations are not ---

    @Test
    fun `blocks other domains entirely`() {
        assertFalse(isAllowedLoginNavigation("https://example.com/oauth2/auth"))
    }

    @Test
    fun `blocks lookalike hosts that merely start with the ravelry host`() {
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com.evil.com/oauth2/auth"))
    }

    @Test
    fun `blocks subdomains other than www`() {
        assertFalse(isAllowedLoginNavigation("https://api.ravelry.com/oauth2/auth"))
    }

    @Test
    fun `blocks plain http even for allowed paths`() {
        assertFalse(isAllowedLoginNavigation("http://www.ravelry.com/account/login"))
    }

    @Test
    fun `blocks unparseable garbage`() {
        assertFalse(isAllowedLoginNavigation("not a url at all"))
    }

    @Test
    fun `blocks a malformed port`() {
        // The one input shape that actually throws inside Ktor's Url parser —
        // "not a url at all" above parses fine and is caught by the scheme check.
        assertFalse(isAllowedLoginNavigation("https://www.ravelry.com:notaport/account/login"))
    }

    @Test
    fun `blocks a lookalike that merely extends the redirect uri`() {
        assertFalse(
            isAllowedLoginNavigation("${RavelryAuthManager.REDIRECT_URI}evil?code=abc&state=xyz"),
        )
    }

    // --- Off-flow handling splits on who initiated the navigation ---
    // The observed dead-end (on-device trace 2026-07-28): a stale authorize challenge
    // bounces the accepted consent through /account/login?prompt=1 to the home page.

    private val homePage = "https://www.ravelry.com/"

    @Test
    fun `allows on-flow navigation regardless of initiator or restart budget`() {
        val url = "https://www.ravelry.com/consent?consent=8ed98e71"
        assertEquals(
            LoginNavigationDecision.ALLOW,
            loginNavigationDecision(url, userInitiated = false, restartsUsed = MAX_LOGIN_FLOW_RESTARTS),
        )
        assertEquals(
            LoginNavigationDecision.ALLOW,
            loginNavigationDecision(url, userInitiated = true, restartsUsed = 0),
        )
    }

    @Test
    fun `a user tap off the flow is blocked in place`() {
        // A browse attempt (issue #425) — never a reason to restart the flow.
        assertEquals(
            LoginNavigationDecision.BLOCK,
            loginNavigationDecision(homePage, userInitiated = true, restartsUsed = 0),
        )
    }

    @Test
    fun `a server redirect off the flow restarts it`() {
        assertEquals(
            LoginNavigationDecision.RESTART_FLOW,
            loginNavigationDecision(homePage, userInitiated = false, restartsUsed = 0),
        )
        assertEquals(
            LoginNavigationDecision.RESTART_FLOW,
            loginNavigationDecision(homePage, userInitiated = false, restartsUsed = MAX_LOGIN_FLOW_RESTARTS - 1),
        )
    }

    @Test
    fun `a server redirect past the restart budget fails the login`() {
        // Give up loudly rather than looping invisibly against a broken server flow.
        assertEquals(
            LoginNavigationDecision.FAIL_LOGIN,
            loginNavigationDecision(homePage, userInitiated = false, restartsUsed = MAX_LOGIN_FLOW_RESTARTS),
        )
    }

    @Test
    fun `an off-site server redirect also restarts rather than escapes`() {
        assertEquals(
            LoginNavigationDecision.RESTART_FLOW,
            loginNavigationDecision("https://example.com/broken", userInitiated = false, restartsUsed = 0),
        )
    }
}
