package com.autom8ed.fibersocial.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest

class RavelryOAuthClientTest {

    @Test
    fun `exchangeAuthCode returns token with fields from response`() = runTest {
        val client = mockOAuthClient(TOKEN_JSON)
        val token = client.exchangeAuthCode("code", "verifier", "https://redirect")
        assertEquals("access123", token.accessToken)
        assertEquals("refresh456", token.refreshToken)
    }

    @Test
    fun `exchangeAuthCode sets expiresAt in the future`() = runTest {
        val before = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val client = mockOAuthClient(TOKEN_JSON)
        val token = client.exchangeAuthCode("code", "verifier", "https://redirect")
        // expires_in=3600 → expiresAt should be ~1 hour from now
        val after = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        assert(token.expiresAt > before)
        assert(token.expiresAt <= after + 3600 * 1000L)
    }

    @Test
    fun `refreshAccessToken returns token with fields from response`() = runTest {
        val client = mockOAuthClient(TOKEN_JSON)
        val token = client.refreshAccessToken("old-refresh")
        assertEquals("access123", token.accessToken)
        assertEquals("refresh456", token.refreshToken)
    }

    @Test
    fun `missing refresh_token in response yields empty string`() = runTest {
        val client = mockOAuthClient(TOKEN_JSON_NO_REFRESH)
        val token = client.exchangeAuthCode("code", "verifier", "https://redirect")
        assertEquals("", token.refreshToken)
    }

    @Test
    fun `AUTH_URL and TOKEN_URL are Ravelry endpoints`() {
        assertEquals("https://www.ravelry.com/oauth2/auth", RavelryOAuthClient.AUTH_URL)
        assertEquals("https://www.ravelry.com/oauth2/token", RavelryOAuthClient.TOKEN_URL)
    }

    @Test
    fun `two calls return independent tokens`() = runTest {
        val firstJson =
            """{"access_token":"first","refresh_token":"r1","expires_in":3600}"""
        val secondJson =
            """{"access_token":"second","refresh_token":"r2","expires_in":7200}"""
        val t1 = mockOAuthClient(firstJson).exchangeAuthCode("c", "v", "u")
        val t2 = mockOAuthClient(secondJson).exchangeAuthCode("c", "v", "u")
        assertNotEquals(t1.accessToken, t2.accessToken)
    }
}
