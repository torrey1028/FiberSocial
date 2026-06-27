package com.autom8ed.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthState {
    object Unauthenticated : AuthState()
    object Loading : AuthState()
    data class Authenticated(val token: AuthToken) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel(
    private val repository: AuthRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun onAuthCodeReceived(authCode: String, codeVerifier: String, clientId: String, redirectUri: String) {
        scope.launch {
            _state.value = AuthState.Loading
            _state.value = try {
                AuthState.Authenticated(repository.login(authCode, codeVerifier, clientId, redirectUri))
            } catch (e: Exception) {
                AuthState.Error(e.message ?: "Login failed")
            }
        }
    }

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

    fun logout() {
        scope.launch {
            repository.logout()
            _state.value = AuthState.Unauthenticated
        }
    }
}
