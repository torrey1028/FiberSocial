package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.AuthToken
import com.autom8ed.fibersocial.auth.SessionExpiredException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

class RavelryApiClientTest {

    @Test
    fun `getCurrentUser returns user from response`() = runTest {
        val client = routingApiClient { CURRENT_USER_JSON }
        val user = client.getCurrentUser()
        assertEquals("yarnie", user.username)
        assertEquals("https://example.com/a.jpg", user.avatarUrl)
    }

    @Test
    fun `getUserGroups scrapes memberships HTML and resolves groups via search`() = runTest {
        val client = routingApiClient { path ->
            when {
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("groups/search") -> GROUPS_JSON
                else -> """{"groups":[]}"""
            }
        }
        val groups = client.getUserGroups("yarnie")
        assertEquals(1, groups.size)
        assertEquals("KAL Hub", groups[0].name)
        assertEquals(42L, groups[0].forumId)
    }

    @Test
    fun `getUserGroups returns empty list when memberships page has no group links`() = runTest {
        val client = routingApiClient { path ->
            when {
                path.contains("memberships") -> "<html><body>no groups here</body></html>"
                else -> """{"groups":[]}"""
            }
        }
        assertEquals(emptyList(), client.getUserGroups("yarnie"))
    }

    @Test
    fun `getUserGroups skips groups not found in search`() = runTest {
        val htmlWithExtra = MEMBERSHIPS_HTML +
            """<a href="https://www.ravelry.com/groups/unknown-group">Unknown</a>"""
        val client = routingApiClient { path ->
            when {
                path.contains("memberships") -> htmlWithExtra
                path.contains("groups/search") -> GROUPS_JSON
                else -> """{"groups":[]}"""
            }
        }
        val groups = client.getUserGroups("yarnie")
        assertEquals(1, groups.size)
        assertTrue(groups.none { it.permalink == "unknown-group" })
    }

    @Test
    fun `getUserGroups falls back to a narrower query when the full permalink query misses`() = runTest {
        // Regression test for #23: Ravelry appends "-2" to disambiguate a group's slug from
        // an existing one, but that digit isn't part of the group's name and pollutes the
        // search query enough that a small/niche group can fall out of the top 25 results.
        val membershipsHtml = """<html><body>
            <a href="https://www.ravelry.com/groups/kirkland-fiber-arts-circle-2">Kirkland Fiber Arts Circle</a>
        </body></html>"""
        val targetGroupJson =
            """{"groups":[{"id":99,"name":"Kirkland Fiber Arts Circle","permalink":"kirkland-fiber-arts-circle-2","forum_id":77}]}"""
        val queriesSeen = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.contains("memberships") -> membershipsHtml
                path.contains("groups/search") -> {
                    val query = request.url.parameters["query"].orEmpty()
                    queriesSeen.add(query)
                    if (query == "kirkland fiber arts circle") targetGroupJson else """{"groups":[]}"""
                }
                else -> """{"groups":[]}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())

        val groups = client.getUserGroups("yarnie")

        assertEquals(1, groups.size)
        assertEquals(77L, groups[0].forumId)
        assertEquals(listOf("kirkland fiber arts circle 2", "kirkland fiber arts circle"), queriesSeen)
    }

    @Test
    fun `getUserGroups omits a group when every fallback query misses`() = runTest {
        val client = routingApiClient { path ->
            when {
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("groups/search") -> """{"groups":[]}"""
                else -> """{"groups":[]}"""
            }
        }
        assertEquals(emptyList(), client.getUserGroups("yarnie"))
    }

    @Test
    fun `getUserGroups fails when no session cookie stored`() = runTest {
        val storage = FakeFeedTokenStorage(
            initial = com.autom8ed.fibersocial.auth.AuthToken("tok", "ref", Long.MAX_VALUE, null)
        )
        val client = routingApiClient(storage = storage) { "" }
        val result = runCatching { client.getUserGroups("yarnie") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `getGroupTopics returns topics list`() = runTest {
        val client = routingApiClient { topicsJson(100L, 101L) }
        val topics = client.getGroupTopics(42L)
        assertEquals(2, topics.size)
        assertEquals(100L, topics[0].id)
        assertEquals(101L, topics[1].id)
    }

    @Test
    fun `getGroupTopics returns empty list when forum has no topics`() = runTest {
        val client = routingApiClient { """{"topics":[]}""" }
        assertEquals(emptyList(), client.getGroupTopics(42L))
    }

    @Test
    fun `getTopicDetail returns full topic with author and summary`() = runTest {
        val client = routingApiClient { topicDetailJson(100L, imagesCount = 2, summary = "Great WIP!") }
        val topic = client.getTopicDetail(100L)
        assertEquals(100L, topic.id)
        assertEquals(2, topic.imagesCount)
        assertEquals("yarnie", topic.createdByUser?.username)
        assertEquals("Great WIP!", topic.summary)
    }

    @Test
    fun `all requests include Bearer token header`() = runTest {
        val storage = FakeFeedTokenStorage()
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(
                content = CURRENT_USER_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        RavelryApiClient(httpClient, storage).getCurrentUser()
        assertEquals("Bearer test-token", capturedAuth)
    }

    @Test
    fun `getTopicPosts returns posts list`() = runTest {
        val client = routingApiClient { postsJson(1L, 2L) }
        val posts = client.getTopicPosts(42L)
        assertEquals(2, posts.size)
        assertEquals(1L, posts[0].id)
        assertEquals("<p>Reply 1</p>", posts[0].bodyHtml)
        assertEquals("user1", posts[0].user?.username)
        assertEquals("2024-01-15T10:00:00Z", posts[0].createdAt)
    }

    @Test
    fun `getTopicPosts returns empty list when topic has no posts`() = runTest {
        val client = routingApiClient { """{"posts":[]}""" }
        assertEquals(emptyList(), client.getTopicPosts(42L))
    }

    @Test
    fun `getCurrentUser throws when no token stored`() = runTest {
        val emptyStorage = FakeFeedTokenStorage(initial = null)
        val engine = MockEngine { respond("", HttpStatusCode.OK, headersOf()) }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        assertFailsWith<IllegalStateException> {
            RavelryApiClient(httpClient, emptyStorage).getCurrentUser()
        }
    }

    @Test
    fun `on 401 retries request after successful token refresh`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) respond("", HttpStatusCode.Unauthorized, headersOf())
            else respond(CURRENT_USER_JSON, HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        var refreshCalled = false
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage(),
            refreshToken = { refreshCalled = true })
        val user = client.getCurrentUser()
        assertEquals("yarnie", user.username)
        assertTrue(refreshCalled)
        assertEquals(2, callCount)
    }

    @Test
    fun `on 401 without refresh callback throws SessionExpiredException`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized, headersOf()) }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        assertFailsWith<SessionExpiredException> {
            RavelryApiClient(httpClient, FakeFeedTokenStorage()).getCurrentUser()
        }
    }

    @Test
    fun `on 401 when refresh throws SessionExpiredException is propagated`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized, headersOf()) }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage(),
            refreshToken = { throw Exception("network error") })
        assertFailsWith<SessionExpiredException> {
            client.getCurrentUser()
        }
    }

    @Test
    fun `on 403 retries request after successful token refresh`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) respond("", HttpStatusCode.Forbidden, headersOf())
            else respond(CURRENT_USER_JSON, HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        var refreshCalled = false
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage(),
            refreshToken = { refreshCalled = true })
        client.getCurrentUser()
        assertTrue(refreshCalled)
        assertEquals(2, callCount)
    }

    @Test
    fun `proactively refreshes when token expires within 60 seconds`() = runTest {
        val nearExpiry = Clock.System.now().toEpochMilliseconds() + 30_000L
        val storage = FakeFeedTokenStorage(
            initial = AuthToken("test-token", "test-refresh", nearExpiry, "sess=test")
        )
        val engine = MockEngine {
            respond(CURRENT_USER_JSON, HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        var refreshCalled = false
        val client = RavelryApiClient(httpClient, storage,
            refreshToken = { refreshCalled = true })
        client.getCurrentUser()
        assertTrue(refreshCalled)
    }
}
