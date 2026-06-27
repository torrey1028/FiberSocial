package com.autom8ed.auth

import kotlinx.serialization.Serializable

@Serializable
data class AuthToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long, // epoch millis
)
