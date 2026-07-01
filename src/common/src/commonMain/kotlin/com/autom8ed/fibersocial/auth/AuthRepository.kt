package com.autom8ed.fibersocial.auth

/**
 * Coordinates the auth lifecycle: login, token refresh, and logout.
 *
 * Acts as the single source of truth for [AuthToken] — all reads and writes go
 * through this class rather than directly through [TokenStorage].
 *
 * @param oauthClient Performs the actual OAuth token exchange / refresh HTTP calls.
 * @param tokenStorage Persists tokens across app restarts.
 */
class AuthRepository(
    private val oauthClient: RavelryOAuthClient,
    private val tokenStorage: TokenStorage,
) {

    /**
     * Completes the OAuth login flow by exchanging [authCode] for a token and saving it.
     *
     * @param authCode One-time authorization code from the OAuth redirect.
     * @param codeVerifier PKCE verifier that matches the challenge sent in the auth request.
     * @param redirectUri Redirect URI registered with Ravelry.
     * @param sessionCookie Optional `www.ravelry.com` cookie string captured from the WebView.
     *   Stored alongside the Bearer token so group membership scraping can reuse it without
     *   requiring re-authentication.
     * @return The newly saved [AuthToken].
     */
    suspend fun login(
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
        sessionCookie: String? = null,
    ): AuthToken {
        val token = oauthClient.exchangeAuthCode(authCode, codeVerifier, redirectUri)
            .copy(sessionCookie = sessionCookie)
        tokenStorage.save(token)
        return token
    }

    /**
     * Refreshes the access token using the stored refresh token.
     *
     * @return The updated [AuthToken] (already persisted).
     * @throws IllegalStateException if no token is currently stored.
     */
    suspend fun refreshToken(): AuthToken {
        val stored = tokenStorage.load() ?: error("No stored token to refresh")
        val refreshed = oauthClient.refreshAccessToken(stored.refreshToken)
            .copy(sessionCookie = stored.sessionCookie)
        tokenStorage.save(refreshed)
        return refreshed
    }

    /**
     * Returns the currently stored [AuthToken], or `null` if the user has never
     * logged in or has logged out.
     */
    suspend fun getStoredToken(): AuthToken? = tokenStorage.load()

    /** Clears all stored credentials, effectively logging the user out. */
    suspend fun logout() = tokenStorage.clear()
}
