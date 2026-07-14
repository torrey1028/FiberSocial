package com.myhobbyislearning.fibersocial.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Identifies FiberSocial's traffic to Ravelry (and any HTTP intermediary watching it) as a
 * good-faith registered app rather than anonymous/bot traffic, with a way to reach the developer.
 * No cross-platform app-version constant is exposed to commonMain today, so this is a fixed
 * string rather than embedding a version number.
 */
internal const val RAVELRY_USER_AGENT =
    "FiberSocial (unofficial Ravelry client; +https://github.com/torrey1028/FiberSocial)"

/** Shared config for every Ravelry HTTP client: lenient JSON decoding, generous timeouts. */
internal fun HttpClientConfig<*>.installRavelryDefaults() {
    install(UserAgent) {
        agent = RAVELRY_USER_AGENT
    }
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
