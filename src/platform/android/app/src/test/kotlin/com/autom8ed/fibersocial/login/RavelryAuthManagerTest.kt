package com.autom8ed.fibersocial.login

import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RavelryAuthManagerTest {

    @Test
    fun `buildAuthUrl requests forum-write and offline scope`() {
        val url = Uri.parse(RavelryAuthManager().buildAuthUrl("client-123"))

        // Space-separated scopes; forum-write authorizes reply/edit/delete, offline yields
        // a refresh token so the user isn't forced to re-login constantly.
        assertEquals("forum-write offline", url.getQueryParameter("scope"))
    }

    @Test
    fun `buildAuthUrl includes the PKCE and client parameters`() {
        val url = Uri.parse(RavelryAuthManager().buildAuthUrl("client-123"))

        assertEquals("code", url.getQueryParameter("response_type"))
        assertEquals("client-123", url.getQueryParameter("client_id"))
        assertEquals(RavelryAuthManager.REDIRECT_URI, url.getQueryParameter("redirect_uri"))
        assertEquals("S256", url.getQueryParameter("code_challenge_method"))
        assertTrue(!url.getQueryParameter("code_challenge").isNullOrBlank())
        assertTrue(!url.getQueryParameter("state").isNullOrBlank())
    }
}
