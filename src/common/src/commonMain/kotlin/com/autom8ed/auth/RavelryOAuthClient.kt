package com.autom8ed.auth

import io.ktor.client.HttpClient

class RavelryOAuthClient(private val httpClient: HttpClient) {

    companion object {
        const val AUTH_URL = "https://www.ravelry.com/oauth2/auth"
        const val TOKEN_URL = "https://www.ravelry.com/oauth2/token"
    }

    suspend fun exchangeAuthCode(
        clientId: String,
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): AuthToken = TODO("Phase 2: POST auth code + verifier to TOKEN_URL, return AuthToken")

    suspend fun refreshAccessToken(
        clientId: String,
        refreshToken: String,
    ): AuthToken = TODO("Phase 2: POST refresh_token grant to TOKEN_URL, return AuthToken")
}
