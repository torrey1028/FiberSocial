package com.myhobbyislearning.fibersocial.auth

import kotlin.test.Test
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
}
