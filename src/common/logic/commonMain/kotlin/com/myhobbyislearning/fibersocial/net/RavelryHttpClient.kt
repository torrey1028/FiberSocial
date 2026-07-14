package com.myhobbyislearning.fibersocial.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Shared config for every Ravelry HTTP client: lenient JSON decoding, generous timeouts. */
internal fun HttpClientConfig<*>.installRavelryDefaults() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
    }
}

/** A [HttpClient] configured for calling the Ravelry API, using the platform's engine. */
expect fun ravelryHttpClient(): HttpClient
