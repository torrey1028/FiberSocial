package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.AuthToken
import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.auth.TokenStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class FakeFeedTokenStorage(
    initial: AuthToken? = AuthToken("test-token", "test-refresh", Long.MAX_VALUE, "sess=test"),
) : TokenStorage {
    private var stored: AuthToken? = initial
    override suspend fun save(token: AuthToken) { stored = token }
    override suspend fun load(): AuthToken? = stored
    override suspend fun clear() { stored = null }
}

fun routingApiClient(
    storage: TokenStorage = FakeFeedTokenStorage(),
    route: (path: String) -> String,
): RavelryApiClient {
    val engine = MockEngine { request ->
        respond(
            content = route(request.url.encodedPath),
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
        )
    }
    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return RavelryApiClient(client, storage)
}

fun errorApiClient(storage: TokenStorage = FakeFeedTokenStorage()): RavelryApiClient {
    val engine = MockEngine { error("Simulated network error") }
    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return RavelryApiClient(client, storage)
}

fun sessionExpiredApiClient(storage: TokenStorage = FakeFeedTokenStorage()): RavelryApiClient {
    val engine = MockEngine { throw SessionExpiredException("Token expired") }
    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return RavelryApiClient(client, storage)
}

fun nullMessageApiClient(storage: TokenStorage = FakeFeedTokenStorage()): RavelryApiClient {
    val engine = MockEngine { throw RuntimeException() }
    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return RavelryApiClient(client, storage)
}

// Minimal fixture JSONs shared across test classes
const val CURRENT_USER_JSON = """{"user":{"username":"yarnie","small_photo_url":"https://example.com/a.jpg"}}"""
const val GROUPS_JSON = """{"groups":[{"id":10,"name":"KAL Hub","permalink":"kal-hub","forum_id":42}]}"""
const val MEMBERSHIPS_HTML = """<html><body>
<a href="https://www.ravelry.com/groups/kal-hub">KAL Hub</a>
</body></html>"""
fun topicsJson(vararg ids: Long) = """{"topics":[${
    ids.joinToString(",") { """{"id":$it,"title":"Topic $it"}""" }
}]}"""
fun postsJson(vararg ids: Long) = """{"posts":[${
    ids.joinToString(",") { id ->
        """{"id":$id,"body_html":"<p>Reply $id</p>","created_at":"2024-01-15T10:00:00Z","user":{"username":"user$id"}}"""
    }
}]}"""

fun voteResponseJson(type: String, count: Int, userVoted: Boolean) = """{
    "vote_totals":{"$type":$count},
    "user_votes":[${if (userVoted) "\"$type\"" else ""}]
}"""

fun topicDetailJson(
    id: Long,
    imagesCount: Int = 0,
    sticky: Boolean = false,
    repliedAt: String? = "2024-01-${(id % 28 + 1).toString().padStart(2, '0')}",
    summary: String? = "Summary for topic $id",
) = """{
  "topic":{
    "id":$id,"title":"Topic $id",
    "forum_images_count":$imagesCount,
    "sticky":$sticky,
    "forum_posts_count":2,
    "replied_at":${if (repliedAt != null) "\"$repliedAt\"" else "null"},
    "created_by_user":{"username":"yarnie"},
    "summary":${if (summary != null) "\"$summary\"" else "null"}
  }
}"""
