package com.myhobbyislearning.fibersocial.auth

import kotlinx.serialization.Serializable

/**
 * Credentials stored after a successful OAuth login.
 *
 * @property accessToken Bearer token for Ravelry API calls.
 * @property refreshToken Opaque token used to obtain a new [accessToken] when it expires.
 *   May be empty if Ravelry omits it in the token response.
 * @property expiresAt Expiry time as epoch milliseconds.
 * @property sessionCookie Full cookie string for `www.ravelry.com`, captured during
 *   WebView OAuth. Required for HTML scraping of pages not exposed by the API
 *   (e.g. group membership lists).
 */
@Serializable
data class AuthToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val sessionCookie: String? = null,
)
