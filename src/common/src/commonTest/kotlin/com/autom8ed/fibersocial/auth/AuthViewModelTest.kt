package com.autom8ed.fibersocial.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

// Ktor's HttpClientEngineBase sets coroutineContext = Dispatchers.IO, so even MockEngine
// runs engine calls on real IO threads. advanceUntilIdle() only advances the test
// scheduler and cannot complete those IO-dispatched coroutines.
//
// Fix: UnconfinedTestDispatcher runs launched coroutines eagerly until they first
// suspend (at the IO boundary). We then join() child jobs directly, which suspends
// the test until the real IO work finishes — dispatcher-agnostic.
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private suspend fun awaitChildren(job: Job) =
        job.children.toList().forEach { it.join() }

    @Test
    fun `initial state is Unauthenticated`() = runTest(UnconfinedTestDispatcher()) {
        val vm = AuthViewModel(AuthRepository(mockOAuthClient(TOKEN_JSON), FakeTokenStorage()), this)
        assertEquals(AuthState.Unauthenticated, vm.state.value)
    }

    @Test
    fun `onAuthCodeReceived transitions to Authenticated on success`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = AuthViewModel(AuthRepository(mockOAuthClient(TOKEN_JSON), FakeTokenStorage()), this)
            vm.onAuthCodeReceived("code", "verifier", "https://redirect")
            awaitChildren(coroutineContext[Job]!!)
            val state = assertIs<AuthState.Authenticated>(vm.state.value)
            assertEquals("access123", state.token.accessToken)
        }

    @Test
    fun `onAuthCodeReceived falls back to a default message when the failure has none`() =
        runTest(UnconfinedTestDispatcher()) {
            val failClient = HttpClient(MockEngine {
                throw RuntimeException()
            }) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = AuthViewModel(
                AuthRepository(RavelryOAuthClient(failClient, "id", "secret"), FakeTokenStorage()),
                this,
            )
            vm.onAuthCodeReceived("code", "verifier", "https://redirect")
            awaitChildren(coroutineContext[Job]!!)
            assertEquals("Login failed", assertIs<AuthState.Error>(vm.state.value).message)
        }

    @Test
    fun `onAuthCodeReceived transitions to Error on network failure`() =
        runTest(UnconfinedTestDispatcher()) {
            val failClient = HttpClient(MockEngine {
                throw java.io.IOException("Simulated network failure")
            }) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = AuthViewModel(
                AuthRepository(RavelryOAuthClient(failClient, "id", "secret"), FakeTokenStorage()),
                this,
            )
            vm.onAuthCodeReceived("code", "verifier", "https://redirect")
            awaitChildren(coroutineContext[Job]!!)
            assertIs<AuthState.Error>(vm.state.value)
        }

    @Test
    fun `checkStoredAuth transitions to Authenticated when token exists`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = FakeTokenStorage()
            val repo = AuthRepository(mockOAuthClient(TOKEN_JSON), storage)
            repo.login("code", "verifier", "https://redirect") // seed storage
            val vm = AuthViewModel(repo, this)
            vm.checkStoredAuth()
            awaitChildren(coroutineContext[Job]!!)
            assertIs<AuthState.Authenticated>(vm.state.value)
        }

    @Test
    fun `checkStoredAuth stays Unauthenticated when no token stored`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = AuthViewModel(AuthRepository(mockOAuthClient(TOKEN_JSON), FakeTokenStorage()), this)
            vm.checkStoredAuth()
            awaitChildren(coroutineContext[Job]!!)
            assertEquals(AuthState.Unauthenticated, vm.state.value)
        }

    @Test
    fun `logout clears state to Unauthenticated`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = FakeTokenStorage()
            val repo = AuthRepository(mockOAuthClient(TOKEN_JSON), storage)
            val vm = AuthViewModel(repo, this)

            vm.onAuthCodeReceived("code", "verifier", "https://redirect")
            awaitChildren(coroutineContext[Job]!!)
            assertIs<AuthState.Authenticated>(vm.state.value)

            vm.logout()
            awaitChildren(coroutineContext[Job]!!)
            assertEquals(AuthState.Unauthenticated, vm.state.value)
        }
}
