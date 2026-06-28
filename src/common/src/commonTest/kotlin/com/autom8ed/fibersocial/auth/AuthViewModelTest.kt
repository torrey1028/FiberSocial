package com.autom8ed.fibersocial.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class AuthViewModelTest {

    private fun makeVm(
        responseJson: String = TOKEN_JSON,
        storage: FakeTokenStorage = FakeTokenStorage(),
        block: (AuthViewModel, FakeTokenStorage) -> Unit = { _, _ -> },
    ) = runTest {
        val repo = AuthRepository(mockOAuthClient(responseJson), storage)
        val vm = AuthViewModel(repo, this)
        block(vm, storage)
    }

    @Test
    fun `initial state is Unauthenticated`() = runTest {
        val repo = AuthRepository(mockOAuthClient(TOKEN_JSON), FakeTokenStorage())
        val vm = AuthViewModel(repo, this)
        assertEquals(AuthState.Unauthenticated, vm.state.value)
    }

    @Test
    fun `onAuthCodeReceived transitions to Authenticated on success`() = runTest {
        val repo = AuthRepository(mockOAuthClient(TOKEN_JSON), FakeTokenStorage())
        val vm = AuthViewModel(repo, this)
        vm.onAuthCodeReceived("code", "verifier", "https://redirect")
        advanceUntilIdle()
        val state = assertIs<AuthState.Authenticated>(vm.state.value)
        assertEquals("access123", state.token.accessToken)
    }

    @Test
    fun `onAuthCodeReceived transitions to Error on network failure`() = runTest {
        val failClient = HttpClient(MockEngine {
            respond("", HttpStatusCode.InternalServerError,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val repo = AuthRepository(
            RavelryOAuthClient(failClient, "id", "secret"),
            FakeTokenStorage(),
        )
        val vm = AuthViewModel(repo, this)
        vm.onAuthCodeReceived("code", "verifier", "https://redirect")
        advanceUntilIdle()
        assertIs<AuthState.Error>(vm.state.value)
    }

    @Test
    fun `checkStoredAuth transitions to Authenticated when token exists`() = runTest {
        val storage = FakeTokenStorage()
        val repo = AuthRepository(mockOAuthClient(TOKEN_JSON), storage)
        val vm = AuthViewModel(repo, this)
        // seed storage via login first
        repo.login("code", "verifier", "https://redirect")

        vm.checkStoredAuth()
        advanceUntilIdle()
        assertIs<AuthState.Authenticated>(vm.state.value)
    }

    @Test
    fun `checkStoredAuth stays Unauthenticated when no token stored`() = runTest {
        val repo = AuthRepository(mockOAuthClient(TOKEN_JSON), FakeTokenStorage())
        val vm = AuthViewModel(repo, this)
        vm.checkStoredAuth()
        advanceUntilIdle()
        assertEquals(AuthState.Unauthenticated, vm.state.value)
    }

    @Test
    fun `logout clears state to Unauthenticated`() = runTest {
        val storage = FakeTokenStorage()
        val repo = AuthRepository(mockOAuthClient(TOKEN_JSON), storage)
        val vm = AuthViewModel(repo, this)
        // authenticate first
        vm.onAuthCodeReceived("code", "verifier", "https://redirect")
        advanceUntilIdle()
        assertIs<AuthState.Authenticated>(vm.state.value)

        vm.logout()
        advanceUntilIdle()
        assertEquals(AuthState.Unauthenticated, vm.state.value)
    }
}
