package com.autom8ed.fibersocial.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters
import kotlinx.datetime.Clock

class RavelryOAuthClient(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
) {

    companion object {
        const val AUTH_URL = "https://www.ravelry.com/oauth2/auth"
        const val TOKEN_URL = "https://www.ravelry.com/oauth2/token"
    }

    suspend fun exchangeAuthCode(
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): AuthToken = httpClient.submitForm(
        url = TOKEN_URL,
        formParameters = parameters {
            append("grant_type", "authorization_code")
            append("code", authCode)
            append("redirect_uri", redirectUri)
            append("code_verifier", codeVerifier)
        }
    ) {
        basicAuth(clientId, clientSecret)
    }.body<TokenResponse>().toAuthToken()

    suspend fun refreshAccessToken(refreshToken: String): AuthToken = httpClient.submitForm(
        url = TOKEN_URL,
        formParameters = parameters {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
        }
    ) {
        basicAuth(clientId, clientSecret)
    }.body<TokenResponse>().toAuthToken()

    private fun TokenResponse.toAuthToken() = AuthToken(
        accessToken = accessToken,
        refreshToken = refreshToken ?: "",
        expiresAt = Clock.System.now().toEpochMilliseconds() + expiresIn * 1000L,
    )
}
