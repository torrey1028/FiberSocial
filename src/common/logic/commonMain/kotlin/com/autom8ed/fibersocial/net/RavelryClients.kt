package com.autom8ed.fibersocial.net

import com.autom8ed.fibersocial.auth.AuthRepository
import com.autom8ed.fibersocial.auth.RavelryOAuthClient
import com.autom8ed.fibersocial.auth.TokenStorage
import com.autom8ed.fibersocial.feed.RavelryApiClient
import io.ktor.client.HttpClient

/** Builds the [AuthRepository] node of the Ravelry client graph. */
fun ravelryAuthRepository(
    httpClient: HttpClient,
    tokenStorage: TokenStorage,
    clientId: String,
    clientSecret: String,
): AuthRepository = AuthRepository(RavelryOAuthClient(httpClient, clientId, clientSecret), tokenStorage)

/** Builds the API client, wired to refresh through [authRepository] on session expiry. */
fun ravelryApiClient(
    httpClient: HttpClient,
    tokenStorage: TokenStorage,
    authRepository: AuthRepository,
): RavelryApiClient = RavelryApiClient(
    httpClient = httpClient,
    tokenStorage = tokenStorage,
    refreshToken = { authRepository.refreshToken() },
)
