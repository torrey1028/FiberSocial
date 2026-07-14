package com.myhobbyislearning.fibersocial.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class RavelryHttpClientTest {
    @Serializable
    private data class Widget(val name: String)

    @Test
    fun `installRavelryDefaults ignores unknown JSON keys`() = runTest {
        val client = HttpClient(MockEngine { request ->
            respond(
                content = """{"name":"gizmo","extra_field":"ignored"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }) {
            installRavelryDefaults()
        }
        try {
            val widget: Widget = client.get("https://example.com").body()
            assertEquals("gizmo", widget.name)
        } finally {
            client.close()
        }
    }

    @Test
    fun `ravelryHttpClient returns a usable client`() {
        // Exercises the platform actual (jvm's CIO here); real HTTP calls are covered
        // per-feature against RavelryApiClient/RavelryOAuthClient with a mock engine.
        val client = ravelryHttpClient()
        client.close()
    }
}
