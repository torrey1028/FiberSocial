package com.autom8ed.fibersocial.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Represents the authentication lifecycle state emitted by [AuthViewModel].
 */
sealed class AuthState {
    /** No credentials are stored; the user must log in. */
    object Unauthenticated : AuthState()

    /** A login or token-check operation is in progress. */
    object Loading : AuthState()

    /**
     * The user is authenticated and [token] is ready for use.
     * @property token The current valid credentials.
     */
    data class Authenticated(val token: AuthToken) : AuthState()

    /**
     * An error occurred during login or token loading.
     * @property message Human-readable description of the failure.
     */
    data class Error(val message: String) : AuthState()
}

/**
 * Platform-agnostic ViewModel that drives the authentication UI state.
 *
 * Exposes a [state] flow that platform ViewModels (e.g. `AuthAndroidViewModel`) collect
 * and forward to Compose.
 *
 * @param repository Auth business logic and token persistence.
 * @param scope Coroutine scope tied to the ViewModel's lifecycle.
 */
class AuthViewModel(
    private val repository: AuthRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Unauthenticated)

    /** Observable auth state. Starts as [AuthState.Unauthenticated]. */
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /**
     * Completes the OAuth flow after the WebView captures the redirect.
     *
     * @param authCode One-time code from the OAuth redirect.
     * @param codeVerifier PKCE verifier generated before the auth URL was built.
     * @param redirectUri Must match the URI used in the original auth request.
     * @param sessionCookie `www.ravelry.com` cookie string captured by the WebView,
     *   stored for later use in HTML scraping.
     */
    fun onAuthCodeReceived(
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
        sessionCookie: String? = null,
    ) {
        scope.launch {
            _state.value = AuthState.Loading
            _state.value = try {
                AuthState.Authenticated(
                    repository.login(authCode, codeVerifier, redirectUri, sessionCookie)
                )
            } catch (e: Exception) {
                AuthState.Error(e.message ?: "Login failed")
            }
        }
    }

    /**
     * Checks for a persisted token on app launch.
     *
     * Transitions to [AuthState.Authenticated] if a token is found,
     * or [AuthState.Unauthenticated] if none exists or loading fails.
     */
    fun checkStoredAuth() {
        scope.launch {
            _state.value = AuthState.Loading
            _state.value = try {
                val token = repository.getStoredToken()
                if (token != null) AuthState.Authenticated(token) else AuthState.Unauthenticated
            } catch (e: Exception) {
                AuthState.Unauthenticated
            }
        }
    }

    /** Clears stored credentials and returns to [AuthState.Unauthenticated]. */
    fun logout() {
        scope.launch {
            repository.logout()
            _state.value = AuthState.Unauthenticated
        }
    }
}
