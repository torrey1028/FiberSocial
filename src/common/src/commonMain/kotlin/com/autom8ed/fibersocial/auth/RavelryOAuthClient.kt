package com.autom8ed.fibersocial.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters
import kotlinx.datetime.Clock

/**
 * Handles the Ravelry OAuth 2.0 token exchange and refresh flows.
 *
 * Ravelry requires HTTP Basic Auth on the token endpoint (client_id + client_secret),
 * in addition to the standard PKCE code verifier.
 *
 * @param httpClient Ktor client configured with JSON content negotiation.
 * @param clientId OAuth application client ID.
 * @param clientSecret OAuth application client secret. Never commit this value;
 *   it is injected via `BuildConfig` from the gitignored `local.properties` for
 *   local builds, and from the `RAVELRY_CLIENT_SECRET` GitHub Actions secret for
 *   CI builds. Note that any credential compiled into a distributed APK is
 *   extractable by decompilation (RFC 8252 §8.5); Ravelry's token endpoint
 *   nevertheless requires Basic client authentication, so a PKCE-only public
 *   client is not an option here.
 */
class RavelryOAuthClient(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
) {

    companion object {
        /** Ravelry authorization endpoint (browser redirect target). */
        const val AUTH_URL = "https://www.ravelry.com/oauth2/auth"

        /** Ravelry token endpoint (server-to-server POST). */
        const val TOKEN_URL = "https://www.ravelry.com/oauth2/token"
    }

    /**
     * Exchanges a one-time authorization code for an [AuthToken].
     *
     * @param authCode Code received in the OAuth redirect callback.
     * @param codeVerifier PKCE code verifier generated before the auth request.
     * @param redirectUri Must exactly match the URI registered with Ravelry.
     * @return A new [AuthToken] with expiry set relative to the current clock.
     */
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

    /**
     * Uses a refresh token to obtain a new [AuthToken] without user interaction.
     *
     * @param refreshToken The refresh token from a previously stored [AuthToken].
     * @return A new [AuthToken]. Note: [AuthToken.sessionCookie] is not carried over;
     *   callers in [AuthRepository] are responsible for preserving it.
     */
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
