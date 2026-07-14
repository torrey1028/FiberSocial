package com.myhobbyislearning.fibersocial.auth

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RavelryAuthManagerTest {
    @Test
    fun `auth url targets the ravelry oauth endpoint with pkce params`() {
        val url = RavelryAuthManager().buildAuthUrl("my-client-id")

        assertTrue(url.startsWith("https://www.ravelry.com/oauth2/auth?"))
        assertTrue("response_type=code" in url)
        assertTrue("client_id=my-client-id" in url)
        assertTrue("redirect_uri=" in url)
        assertTrue("code_challenge=" in url)
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("state=" in url)
    }

    @Test
    fun `auth url requests forum-write message-write and offline scope`() {
        val url = RavelryAuthManager().buildAuthUrl("my-client-id")

        // forum-write authorizes reply/edit/delete; message-write authorizes posting
        // project comments (issue #103); offline yields a refresh token so the user
        // isn't forced to re-login constantly.
        assertEquals("forum-write message-write offline", Url(url).parameters["scope"])
    }

    @Test
    fun `redirect uri is percent-encoded in the query string`() {
        val url = RavelryAuthManager().buildAuthUrl("client")
        assertTrue("redirect_uri=fibersocial%3A%2F%2Fauth%2Fcallback" in url)
    }

    @Test
    fun `consumeCodeVerifier throws before buildAuthUrl is called`() {
        assertFailsWith<IllegalStateException> { RavelryAuthManager().consumeCodeVerifier() }
    }

    @Test
    fun `consumeCodeVerifier returns the verifier used to build the challenge in the url`() {
        val manager = RavelryAuthManager()
        val url = manager.buildAuthUrl("client")
        val challenge = Regex("code_challenge=([^&]+)").find(url)!!.groupValues[1]

        val verifier = manager.consumeCodeVerifier()

        assertEquals(challenge, base64UrlEncode(sha256(verifier.encodeToByteArray())))
    }

    @Test
    fun `consumeCodeVerifier is one-time use`() {
        val manager = RavelryAuthManager()
        manager.buildAuthUrl("client")
        manager.consumeCodeVerifier()
        assertFailsWith<IllegalStateException> { manager.consumeCodeVerifier() }
    }

    @Test
    fun `each call generates a fresh verifier`() {
        val manager = RavelryAuthManager()
        manager.buildAuthUrl("client")
        val first = manager.consumeCodeVerifier()
        manager.buildAuthUrl("client")
        val second = manager.consumeCodeVerifier()
        assertTrue(first != second)
    }

    @Test
    fun `validateState accepts the state embedded in the auth url`() {
        val manager = RavelryAuthManager()
        val url = manager.buildAuthUrl("client")
        val state = Url(url).parameters["state"]
        assertTrue(manager.validateState(state))
    }

    @Test
    fun `validateState rejects a mismatched state`() {
        val manager = RavelryAuthManager()
        manager.buildAuthUrl("client")
        assertFalse(manager.validateState("not-the-issued-state"))
    }

    @Test
    fun `validateState rejects a missing returned state`() {
        val manager = RavelryAuthManager()
        manager.buildAuthUrl("client")
        assertFalse(manager.validateState(null))
    }

    @Test
    fun `validateState rejects before any auth url is built`() {
        assertFalse(RavelryAuthManager().validateState("anything"))
    }

    @Test
    fun `validateState is one-time use`() {
        val manager = RavelryAuthManager()
        val url = manager.buildAuthUrl("client")
        val state = Url(url).parameters["state"]
        assertTrue(manager.validateState(state))
        // Replaying the same state after it's been consumed must not pass.
        assertFalse(manager.validateState(state))
    }
}
