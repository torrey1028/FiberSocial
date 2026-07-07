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

/** In-memory [GroupOrderStore]; [stored] exposes what the code under test persisted. */
class FakeGroupOrderStore(initial: List<Long>? = null) : GroupOrderStore {
    var stored: List<Long>? = initial
        private set

    override suspend fun load(): List<Long>? = stored
    override suspend fun save(order: List<Long>) { stored = order }
}

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

/**
 * Like [routingApiClient], but [route] may suspend — lets a test gate a specific request
 * (e.g. by `url.parameters["page"]`) on a [kotlinx.coroutines.CompletableDeferred] to
 * deterministically construct an in-flight-request race window.
 */
fun suspendableRoutingApiClient(
    storage: TokenStorage = FakeFeedTokenStorage(),
    route: suspend (url: io.ktor.http.Url) -> String,
): RavelryApiClient {
    val engine = MockEngine { request ->
        respond(
            content = route(request.url),
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
        )
    }
    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return RavelryApiClient(client, storage)
}

fun routingApiClientCapturing(
    storage: TokenStorage = FakeFeedTokenStorage(),
    onRequest: (io.ktor.http.Url) -> Unit,
    route: (path: String) -> String,
): RavelryApiClient {
    val engine = MockEngine { request ->
        onRequest(request.url)
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
        """{"id":$id,"body_html":"<p>Reply $id</p>","body":"Reply $id","editable":true,"created_at":"2024-01-15T10:00:00Z","user":{"username":"user$id"}}"""
    }
}]}"""

/** Like [postsJson] but carrying a paginator, so load-more pagination can be exercised. */
fun postsPageJson(page: Int, pageCount: Int, vararg ids: Long) = """{"posts":[${
    ids.joinToString(",") { id ->
        """{"id":$id,"body_html":"<p>Reply $id</p>","body":"Reply $id","editable":true,"created_at":"2024-01-15T10:00:00Z","user":{"username":"user$id"}}"""
    }
}],"paginator":{"page":$page,"page_count":$pageCount}}"""

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
    postsCount: Int = 2,
    summaryHtml: String? = null,
    lastRead: Int = 0,
    starter: String = "yarnie",
) = """{
  "topic":{
    "id":$id,"title":"Topic $id",
    "forum_images_count":$imagesCount,
    "sticky":$sticky,
    "forum_posts_count":$postsCount,
    "replied_at":${if (repliedAt != null) "\"$repliedAt\"" else "null"},
    "created_by_user":{"username":"$starter"},
    "summary":${if (summary != null) "\"$summary\"" else "null"},
    "summary_html":${if (summaryHtml != null) "\"$summaryHtml\"" else "null"},
    "last_read":$lastRead
  }
}"""

fun topicCreateResponseJson(
    id: Long = 7001L,
    title: String = "My new topic",
    username: String = "yarnie",
) = """{"topic":{"id":$id,"title":"$title","forum_id":123,"forum_posts_count":1,
    "created_at":"2026-07-03T10:00:00Z","created_by_user":{"username":"$username"},
    "summary":"Opening post"},
    "forum_post":{"id":9001,"body_html":"<p>Opening post</p>","created_at":"2026-07-03T10:00:00Z","user":{"username":"$username"}}}"""

fun replyResponseJson(
    id: Long = 99L,
    username: String = "yarnie",
    bodyHtml: String = "<p>My new reply</p>",
) = """{"forum_post":{"id":$id,"body_html":"$bodyHtml","created_at":"2024-01-17T10:00:00Z","user":{"username":"$username"}}}"""

const val TOKEN_PAGE_HTML = """<html><head>
<meta id="authenticity-token" name="csrf-token" content="tok-abc123"/>
</head><body>home</body></html>"""

fun forumPostJson(
    id: Long = 1L,
    body: String = "edited body",
    bodyHtml: String = "<p>edited body</p>",
) = """{"forum_post":{"id":$id,"body":"$body","body_html":"$bodyHtml","editable":true,"user":{"username":"user$id"}}}"""
