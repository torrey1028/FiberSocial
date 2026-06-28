package com.autom8ed.auth

class AuthRepository(
    private val oauthClient: RavelryOAuthClient,
    private val tokenStorage: TokenStorage,
) {
    suspend fun login(authCode: String, codeVerifier: String, redirectUri: String): AuthToken {
        val token = oauthClient.exchangeAuthCode(authCode, codeVerifier, redirectUri)
        tokenStorage.save(token)
        return token
    }

    suspend fun refreshToken(): AuthToken {
        val stored = tokenStorage.load() ?: error("No stored token to refresh")
        val refreshed = oauthClient.refreshAccessToken(stored.refreshToken)
        tokenStorage.save(refreshed)
        return refreshed
    }

    suspend fun getStoredToken(): AuthToken? = tokenStorage.load()

    suspend fun logout() = tokenStorage.clear()
}
