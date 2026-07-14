package com.myhobbyislearning.fibersocial.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Raw JSON shape returned by the Ravelry `/oauth2/token` endpoint.
 *
 * @property accessToken Bearer token for API requests.
 * @property refreshToken Opaque token for future refreshes. Ravelry may omit this field,
 *   so it is nullable.
 * @property expiresIn Lifetime of [accessToken] in seconds from the time of issue.
 */
@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Int,
)
