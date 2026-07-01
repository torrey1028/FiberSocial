package com.autom8ed.fibersocial.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class AuthRepositoryTest {

    private fun makeRepo(
        responseJson: String = TOKEN_JSON,
        storage: FakeTokenStorage = FakeTokenStorage(),
    ) = AuthRepository(mockOAuthClient(responseJson), storage)

    @Test
    fun `login exchanges code and returns token`() = runTest {
        val token = makeRepo().login("code", "verifier", "https://redirect")
        assertEquals("access123", token.accessToken)
        assertEquals("refresh456", token.refreshToken)
    }

    @Test
    fun `login saves token to storage`() = runTest {
        val storage = FakeTokenStorage()
        val repo = makeRepo(storage = storage)
        val token = repo.login("code", "verifier", "https://redirect")
        assertEquals(token, storage.load())
    }

    @Test
    fun `getStoredToken returns null when storage is empty`() = runTest {
        assertNull(makeRepo().getStoredToken())
    }

    @Test
    fun `getStoredToken returns previously saved token`() = runTest {
        val storage = FakeTokenStorage()
        val repo = makeRepo(storage = storage)
        val token = repo.login("code", "verifier", "https://redirect")
        assertEquals(token, repo.getStoredToken())
    }

    @Test
    fun `refreshToken loads stored token and exchanges it`() = runTest {
        val storage = FakeTokenStorage()
        val repo = makeRepo(storage = storage)
        repo.login("code", "verifier", "https://redirect")

        val refreshJson =
            """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""
        val refreshRepo = AuthRepository(mockOAuthClient(refreshJson), storage)
        val refreshed = refreshRepo.refreshToken()

        assertEquals("new-access", refreshed.accessToken)
        assertEquals("new-access", storage.load()?.accessToken)
    }

    @Test
    fun `refreshToken throws when no stored token`() = runTest {
        assertFailsWith<IllegalStateException> {
            makeRepo().refreshToken()
        }
    }

    @Test
    fun `logout clears storage`() = runTest {
        val storage = FakeTokenStorage()
        val repo = makeRepo(storage = storage)
        repo.login("code", "verifier", "https://redirect")
        repo.logout()
        assertNull(storage.load())
    }

    @Test
    fun `login stores sessionCookie alongside token`() = runTest {
        val storage = FakeTokenStorage()
        val repo = makeRepo(storage = storage)
        repo.login("code", "verifier", "https://redirect", sessionCookie = "sess=abc")
        assertEquals("sess=abc", storage.load()?.sessionCookie)
    }

    @Test
    fun `login with null sessionCookie stores null`() = runTest {
        val storage = FakeTokenStorage()
        val repo = makeRepo(storage = storage)
        repo.login("code", "verifier", "https://redirect", sessionCookie = null)
        assertNull(storage.load()?.sessionCookie)
    }

    @Test
    fun `refreshToken preserves sessionCookie from stored token`() = runTest {
        val storage = FakeTokenStorage()
        val repo = makeRepo(storage = storage)
        repo.login("code", "verifier", "https://redirect", sessionCookie = "sess=abc")

        val refreshJson =
            """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""
        val refreshed = AuthRepository(mockOAuthClient(refreshJson), storage).refreshToken()

        assertEquals("sess=abc", refreshed.sessionCookie)
        assertEquals("sess=abc", storage.load()?.sessionCookie)
    }
}
