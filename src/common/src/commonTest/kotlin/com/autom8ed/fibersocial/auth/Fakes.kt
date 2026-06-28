package com.autom8ed.fibersocial.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class FakeTokenStorage : TokenStorage {
    private var stored: AuthToken? = null

    override suspend fun save(token: AuthToken) { stored = token }
    override suspend fun load(): AuthToken? = stored
    override suspend fun clear() { stored = null }
}

fun mockHttpClient(
    responseJson: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpClient = HttpClient(MockEngine {
    respond(
        content = responseJson,
        status = status,
        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
    )
}) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

fun mockOAuthClient(responseJson: String) =
    RavelryOAuthClient(mockHttpClient(responseJson), "client-id", "client-secret")

const val TOKEN_JSON =
    """{"access_token":"access123","refresh_token":"refresh456","expires_in":3600}"""
const val TOKEN_JSON_NO_REFRESH =
    """{"access_token":"access123","expires_in":3600}"""
