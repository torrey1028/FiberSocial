package com.myhobbyislearning.fibersocial.net

import com.myhobbyislearning.fibersocial.auth.AuthToken
import com.myhobbyislearning.fibersocial.auth.FakeTokenStorage
import com.myhobbyislearning.fibersocial.auth.TOKEN_JSON
import com.myhobbyislearning.fibersocial.auth.mockHttpClient
import com.myhobbyislearning.fibersocial.feed.CURRENT_USER_JSON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class RavelryClientsTest {
    @Test
    fun `ravelryAuthRepository logs in through the wired oauth client`() = runTest {
        val storage = FakeTokenStorage()
        val repository = ravelryAuthRepository(
            httpClient = mockHttpClient(TOKEN_JSON),
            tokenStorage = storage,
            clientId = "client-id",
            clientSecret = "client-secret",
        )

        val token = repository.login("code", "verifier", "https://redirect")

        assertEquals("access123", token.accessToken)
        assertEquals(token, storage.load())
    }

    @Test
    fun `ravelryApiClient wires up successfully and can call the api`() = runTest {
        val storage = FakeTokenStorage()
        storage.seedToken(AuthToken("access", "refresh", Long.MAX_VALUE))
        val repository = ravelryAuthRepository(
            httpClient = mockHttpClient(TOKEN_JSON),
            tokenStorage = storage,
            clientId = "client-id",
            clientSecret = "client-secret",
        )
        val apiClient = ravelryApiClient(
            httpClient = mockHttpClient(CURRENT_USER_JSON),
            tokenStorage = storage,
            authRepository = repository,
        )

        val user = apiClient.getCurrentUser()

        assertEquals("yarnie", user.username)
    }

    @Test
    fun `ravelryApiClient's refresh callback reaches the wired auth repository`() = runTest {
        val storage = FakeTokenStorage()
        // Near-expiry with a non-empty refresh token: accessToken() refreshes
        // proactively before the call, exercising the refreshToken callback that
        // wires ravelryApiClient to ravelryAuthRepository.
        storage.seedToken(AuthToken("stale-access", "refresh", 0L))
        val repository = ravelryAuthRepository(
            httpClient = mockHttpClient(TOKEN_JSON),
            tokenStorage = storage,
            clientId = "client-id",
            clientSecret = "client-secret",
        )
        val apiClient = ravelryApiClient(
            httpClient = mockHttpClient(CURRENT_USER_JSON),
            tokenStorage = storage,
            authRepository = repository,
        )

        apiClient.getCurrentUser()

        assertEquals("access123", storage.load()?.accessToken)
    }
}
