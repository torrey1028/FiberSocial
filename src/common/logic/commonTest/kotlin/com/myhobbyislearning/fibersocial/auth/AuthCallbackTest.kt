package com.myhobbyislearning.fibersocial.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Issue #394. The bug being fixed was a silent early return on any redirect without a
 * `code`, so the cases that matter most here are the ones that used to do nothing.
 */
class AuthCallbackTest {

    private val redirect = "fibersocial://auth/callback"

    @Test
    fun `a code redirect parses into a success with its state`() {
        val result = parseAuthCallback("$redirect?code=abc123&state=xyz789")

        assertEquals(AuthCallback.Success(code = "abc123", state = "xyz789"), result)
    }

    @Test
    fun `a code redirect without state still succeeds`() {
        // State validation is the caller's job (issue #149) and its absence is reported
        // there; parsing must not swallow the callback before it gets that far.
        val result = parseAuthCallback("$redirect?code=abc123")

        assertEquals(AuthCallback.Success(code = "abc123", state = null), result)
    }

    @Test
    fun `an error redirect parses into a failure carrying its description`() {
        val result = parseAuthCallback(
            "$redirect?error=invalid_scope" +
                "&error_description=The+requested+scope+is+invalid%2C+unknown%2C+or+malformed",
        )

        assertEquals(
            AuthCallback.Failure(
                error = "invalid_scope",
                description = "The requested scope is invalid, unknown, or malformed",
            ),
            result,
        )
    }

    @Test
    fun `an error redirect without a description still parses as a failure`() {
        val result = parseAuthCallback("$redirect?error=server_error")

        assertEquals(AuthCallback.Failure(error = "server_error", description = null), result)
    }

    @Test
    fun `an error wins over a code on the same redirect`() {
        // Defensive ordering: a server reporting a failure must never be read as success
        // because some other parameter happened to be present.
        val result = parseAuthCallback("$redirect?code=abc123&error=access_denied")

        assertIs<AuthCallback.Failure>(result)
        assertEquals("access_denied", result.error)
    }

    @Test
    fun `a redirect with neither a code nor an error is malformed`() {
        // Previously this silently did nothing and froze the screen.
        assertEquals(AuthCallback.Malformed, parseAuthCallback(redirect))
    }

    @Test
    fun `blank parameter values count as absent`() {
        assertEquals(AuthCallback.Malformed, parseAuthCallback("$redirect?code=&error="))
    }

    @Test
    fun `an unparseable url is malformed rather than an exception`() {
        assertEquals(AuthCallback.Malformed, parseAuthCallback("::not a url::"))
    }

    @Test
    fun `a denied sign-in reads as cancelled rather than as a failure`() {
        // The common case by far: the user tapped Deny. It is a choice, not a fault, and
        // the server's own description for it is unhelpfully technical.
        val message = authFailureMessage(
            AuthCallback.Failure("access_denied", "The resource owner denied the request"),
        )

        assertEquals("Sign-in was cancelled.", message)
    }

    @Test
    fun `other failures prefer the server's own description`() {
        val message = authFailureMessage(
            AuthCallback.Failure("invalid_scope", "The requested scope is invalid"),
        )

        assertEquals("The requested scope is invalid", message)
    }

    @Test
    fun `a failure with no description still names the error`() {
        val message = authFailureMessage(AuthCallback.Failure("server_error", null))

        assertTrue(message.contains("server_error"), "should not report an empty reason")
    }

    @Test
    fun `no failure message leaks a status code the session-expiry UI matches on`() {
        // Parts of the UI pattern-match "401"/"403" to detect an expired session; a login
        // failure carrying those digits would misroute (see RavelryApiClient's
        // forbiddenMessage for the same guard).
        val messages = listOf("access_denied", "invalid_scope", "server_error")
            .map { authFailureMessage(AuthCallback.Failure(it, null)) }

        messages.forEach {
            assertTrue("401" !in it && "403" !in it, "leaked a status code: $it")
        }
    }
}
