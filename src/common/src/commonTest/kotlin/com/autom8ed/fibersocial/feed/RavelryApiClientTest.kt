package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.AuthToken
import com.autom8ed.fibersocial.auth.ForbiddenException
import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.models.VoteType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `getCurrentUser fails loudly when the response is missing the user field`() = runTest {
        val client = routingApiClient { "{}" }
        assertFailsWith<kotlinx.serialization.MissingFieldException> { client.getCurrentUser() }
    }

    @Test
    fun `getUserProfile hits the people endpoint and parses the profile`() = runTest {
        var captured: io.ktor.http.Url? = null
        val client = routingApiClientCapturing(onRequest = { captured = it }) {
            """{"user":{"id":9,"username":"yarnie","first_name":"Yarnie","location":"Seattle",
                "about_me_html":"<p>hi</p>","large_photo_url":"https://img/l.jpg",
                "photo_url":"https://img/p.jpg","small_photo_url":"https://img/s.jpg"}}"""
        }
        val profile = client.getUserProfile("yarnie")
        assertEquals("/people/yarnie.json", captured?.encodedPath)
        assertEquals("yarnie", profile.username)
        assertEquals("Yarnie", profile.firstName)
        assertEquals("Seattle", profile.location)
        assertEquals("https://img/l.jpg", profile.avatarUrl)
    }

    @Test
    fun `getUserProfile avatar falls back through the photo sizes`() = runTest {
        val client = routingApiClient { """{"user":{"id":9,"username":"yarnie","small_photo_url":"https://img/s.jpg"}}""" }
        assertEquals("https://img/s.jpg", client.getUserProfile("yarnie").avatarUrl)
    }

    @Test
    fun `getUserProfile fails loudly when the response is missing the user`() = runTest {
        assertFailsWith<Exception> { routingApiClient { "{}" }.getUserProfile("yarnie") }
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
    fun `getUserGroups carries badge_url through to Group badgeUrl`() = runTest {
        // Guards the badge_url field name end-to-end (issue #167 drawer badge): the search
        // response carries it and it must survive deserialization to Group.badgeUrl.
        val client = routingApiClient { path ->
            when {
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("groups/search") ->
                    """{"groups":[{"id":10,"name":"KAL Hub","permalink":"kal-hub","forum_id":42,"badge_url":"https://img.example/kal.png"}]}"""
                else -> """{"groups":[]}"""
            }
        }
        assertEquals("https://img.example/kal.png", client.getUserGroups("yarnie").single().badgeUrl)
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
    fun `getUserGroups resolves multiple memberships concurrently`() = runTest {
        val membershipsHtml = """<html><body>
            <a href="https://www.ravelry.com/groups/kal-hub">KAL Hub</a>
            <a href="https://www.ravelry.com/groups/sock-knitters">Sock Knitters</a>
        </body></html>"""
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.contains("memberships") -> membershipsHtml
                path.contains("groups/search") -> {
                    val query = request.url.parameters["query"].orEmpty()
                    if (query.contains("sock")) {
                        """{"groups":[{"id":20,"name":"Sock Knitters","permalink":"sock-knitters","forum_id":43}]}"""
                    } else {
                        GROUPS_JSON
                    }
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

        assertEquals(setOf("kal-hub", "sock-knitters"), groups.map { it.permalink }.toSet())
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
    fun `getUserGroups omits a group when search response omits the groups field entirely`() = runTest {
        val client = routingApiClient { path ->
            when {
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("groups/search") -> "{}"
                else -> "{}"
            }
        }
        assertEquals(emptyList(), client.getUserGroups("yarnie"))
    }

    @Test
    fun `getUserGroups propagates a network failure while scraping memberships`() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("memberships")) {
                throw RuntimeException("network unreachable")
            }
            respond(GROUPS_JSON, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())
        val result = runCatching { client.getUserGroups("yarnie") }
        assertTrue(result.isFailure)
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
        val page = client.getGroupTopics(42L)
        assertEquals(2, page.topics.size)
        assertEquals(100L, page.topics[0].id)
        assertEquals(101L, page.topics[1].id)
    }

    @Test
    fun `getGroupTopics defaults to empty when the response omits the topics field`() = runTest {
        val client = routingApiClient { "{}" }
        assertEquals(emptyList(), client.getGroupTopics(42L).topics)
    }

    @Test
    fun `getGroupTopics tolerates a partial paginator object`() = runTest {
        val client = routingApiClient { """{"topics":[{"id":100,"title":"Topic 100"}],"paginator":{"page":2}}""" }
        val page = client.getGroupTopics(42L)
        assertEquals(1, page.topics.size)
        assertEquals(100L, page.topics[0].id)
    }

    @Test
    fun `getGroupTopics returns empty list when forum has no topics`() = runTest {
        val client = routingApiClient { """{"topics":[]}""" }
        assertEquals(emptyList(), client.getGroupTopics(42L).topics)
    }

    @Test
    fun `getGroupTopics reports hasMore true when more pages remain`() = runTest {
        val client = routingApiClient {
            """{"topics":[{"id":100,"title":"Topic 100"}],"paginator":{"page":1,"page_count":3,"results":60}}"""
        }
        val page = client.getGroupTopics(42L)
        assertTrue(page.hasMore)
        assertEquals(1, page.page)
    }

    @Test
    fun `getGroupTopics reports hasMore false on the last page`() = runTest {
        val client = routingApiClient {
            """{"topics":[{"id":100,"title":"Topic 100"}],"paginator":{"page":3,"page_count":3,"results":60}}"""
        }
        val page = client.getGroupTopics(42L)
        assertFalse(page.hasMore)
        assertEquals(3, page.page)
    }

    @Test
    fun `getGroupTopics reports hasMore false when the paginator is absent`() = runTest {
        val client = routingApiClient { topicsJson(100L) }
        assertFalse(client.getGroupTopics(42L).hasMore)
    }

    @Test
    fun `getGroupTopics sends the requested page and page size`() = runTest {
        var capturedUrl: io.ktor.http.Url? = null
        val client = routingApiClientCapturing(onRequest = { capturedUrl = it }) { topicsJson(100L) }
        client.getGroupTopics(42L, page = 2, pageSize = 50)
        assertEquals("2", capturedUrl?.parameters?.get("page"))
        assertEquals("50", capturedUrl?.parameters?.get("page_size"))
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
    fun `getTopicPosts defaults to empty when the response omits the posts field entirely`() = runTest {
        val client = routingApiClient { "{}" }
        assertEquals(emptyList(), client.getTopicPosts(42L))
    }

    @Test
    fun `getTopicPosts requests vote_totals and user_votes`() = runTest {
        var capturedInclude: String? = null
        val engine = MockEngine { request ->
            capturedInclude = request.url.parameters["include"]
            respond(postsJson(1L), HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        RavelryApiClient(httpClient, FakeFeedTokenStorage()).getTopicPosts(42L)
        assertEquals("vote_totals user_votes", capturedInclude)
    }

    @Test
    fun `getTopicPosts merges top-level vote_totals and user_votes onto matching post by id`() = runTest {
        // Ravelry doesn't nest vote_totals/user_votes inside each post in this endpoint's
        // response — it returns them as separate maps keyed by post id (as a string),
        // alongside the posts array.
        val client = routingApiClient {
            """{"posts":[{"id":1,"body_html":"<p>Reply</p>","user":{"username":"user1"}}],
                "vote_totals":{"1":{"love":3}},"user_votes":{"1":["love"]}}"""
        }
        val posts = client.getTopicPosts(42L)
        assertEquals(mapOf("love" to 3), posts[0].voteTotals)
        assertEquals(listOf("love"), posts[0].userVotes)
    }

    @Test
    fun `getTopicPosts matches each post's own vote data by id rather than another post's`() = runTest {
        val client = routingApiClient {
            """{"posts":[
                {"id":1,"body_html":"<p>A</p>","user":{"username":"user1"}},
                {"id":2,"body_html":"<p>B</p>","user":{"username":"user2"}}
               ],
               "vote_totals":{"1":{"interesting":1,"agree":2},"2":{"funny":1}},
               "user_votes":{"1":["agree"],"2":[]}}"""
        }
        val posts = client.getTopicPosts(42L)
        assertEquals(mapOf("interesting" to 1, "agree" to 2), posts[0].voteTotals)
        assertEquals(listOf("agree"), posts[0].userVotes)
        assertEquals(mapOf("funny" to 1), posts[1].voteTotals)
        assertEquals(emptyList(), posts[1].userVotes)
    }

    @Test
    fun `getTopicPosts defaults vote fields to empty when absent`() = runTest {
        val client = routingApiClient { postsJson(1L) }
        val posts = client.getTopicPosts(42L)
        assertEquals(emptyMap(), posts[0].voteTotals)
        assertEquals(emptyList(), posts[0].userVotes)
    }

    @Test
    fun `voteOnPost posts to forum_posts vote endpoint with the given type`() = runTest {
        var capturedPath: String? = null
        var capturedMethod: String? = null
        var capturedType: String? = null
        var capturedVote: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedMethod = request.method.value
            capturedType = request.url.parameters["type"]
            capturedVote = request.url.parameters["vote"]
            respond(voteResponseJson("love", 1, userVoted = true), HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val result = RavelryApiClient(httpClient, FakeFeedTokenStorage())
            .voteOnPost(1000L, VoteType.LOVE, voted = true)

        assertEquals("/forum_posts/1000/vote.json", capturedPath)
        assertEquals("POST", capturedMethod)
        assertEquals("love", capturedType)
        assertEquals("1", capturedVote)
        assertEquals(mapOf("love" to 1), result.voteTotals)
        assertEquals(listOf("love"), result.userVotes)
    }

    @Test
    fun `voteOnPost passes through non-love vote types`() = runTest {
        var capturedType: String? = null
        val engine = MockEngine { request ->
            capturedType = request.url.parameters["type"]
            respond(voteResponseJson("funny", 1, userVoted = true), HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val result = RavelryApiClient(httpClient, FakeFeedTokenStorage())
            .voteOnPost(1000L, VoteType.FUNNY, voted = true)

        assertEquals("funny", capturedType)
        assertEquals(mapOf("funny" to 1), result.voteTotals)
        assertEquals(listOf("funny"), result.userVotes)
    }

    @Test
    fun `voteOnPost defaults vote fields to empty when the response omits them`() = runTest {
        val client = routingApiClient { "{}" }
        val result = client.voteOnPost(1000L, VoteType.LOVE, voted = true)
        assertEquals(emptyMap(), result.voteTotals)
        assertEquals(emptyList(), result.userVotes)
    }

    @Test
    fun `voteOnPost clears vote when voted is false`() = runTest {
        var capturedVote: String? = null
        val engine = MockEngine { request ->
            capturedVote = request.url.parameters["vote"]
            respond(voteResponseJson("love", 0, userVoted = false), HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val result = RavelryApiClient(httpClient, FakeFeedTokenStorage())
            .voteOnPost(1000L, VoteType.LOVE, voted = false)

        assertEquals("0", capturedVote)
        assertEquals(emptyList(), result.userVotes)
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
    fun `on 403 throws ForbiddenException without refreshing or retrying`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            respond("", HttpStatusCode.Forbidden, headersOf())
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        var refreshCalled = false
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage(),
            refreshToken = { refreshCalled = true })
        assertFailsWith<ForbiddenException> { client.getCurrentUser() }
        assertFalse(refreshCalled)
        assertEquals(1, callCount)
    }

    @Test
    fun `on 401 when retry also returns 401 throws SessionExpiredException`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized, headersOf()) }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage(),
            refreshToken = { /* refresh succeeds, but retry still fails */ })
        assertFailsWith<SessionExpiredException> {
            client.getCurrentUser()
        }
    }

    @Test
    fun `on 401 when retry returns 403 throws ForbiddenException`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) respond("", HttpStatusCode.Unauthorized, headersOf())
            else respond("", HttpStatusCode.Forbidden, headersOf())
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage(),
            refreshToken = { /* refresh succeeds, but retry is forbidden */ })
        assertFailsWith<ForbiddenException> {
            client.getCurrentUser()
        }
    }

    @Test
    fun `ForbiddenException message avoids status-code digits that UI matches as expiry`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Forbidden, headersOf()) }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())
        val e = assertFailsWith<ForbiddenException> { client.getCurrentUser() }
        val message = e.message ?: ""
        assertFalse(message.contains("403"))
        assertFalse(message.contains("401"))
        assertTrue(message.contains("/current_user.json"))
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

    @Test
    fun `getGroupEvents scrapes the group page with the session cookie`() = runTest {
        var requestedUrl = ""
        var sentCookie: String? = null
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            sentCookie = request.headers[HttpHeaders.Cookie]
            respond(GROUP_PAGE_EVENTS_HTML, HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Text.Html.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())

        val events = client.getGroupEvents("kirkland-fiber-arts-circle-2")

        assertEquals("https://www.ravelry.com/groups/kirkland-fiber-arts-circle-2", requestedUrl)
        assertEquals("sess=test", sentCookie)
        assertEquals(2, events.size)
        assertEquals("sunday-circle-at-postdoc-brewing-10", events[0].permalink)
        assertEquals(1, events[0].attendeeCount)
    }

    @Test
    fun `getGroupEvents returns empty list for a group without events`() = runTest {
        val client = routingApiClient { "<html><body>no box</body></html>" }
        assertEquals(emptyList(), client.getGroupEvents("quiet-group"))
    }

    @Test
    fun `getGroupEvents throws SessionExpiredException on 401`() = runTest {
        val client = htmlApiClient(MockEngine { _ ->
            respond("", HttpStatusCode.Unauthorized)
        })
        assertFailsWith<SessionExpiredException> { client.getGroupEvents("quiet-group") }
    }

    @Test
    fun `getGroupEvents throws ForbiddenException on 403 rather than bouncing to login`() = runTest {
        // A 403 means the session is valid but the page is off-limits (permission), not
        // expiry — it must not surface as SessionExpiredException (issue #82).
        val client = htmlApiClient(MockEngine { _ ->
            respond("", HttpStatusCode.Forbidden)
        })
        val e = assertFailsWith<ForbiddenException> { client.getGroupEvents("members-only-group") }
        // FeedErrorState pattern-matches "401"/"403" in the message to detect expired
        // sessions — a message containing that digit would defeat this classification.
        val message = e.message ?: ""
        assertFalse(message.contains("403"))
        assertFalse(message.contains("401"))
    }

    @Test
    fun `getGroupEvents throws SessionExpiredException when redirected to the login page`() = runTest {
        // An expired session cookie doesn't 401 on www.ravelry.com — it 302s to the login
        // page, which Ktor follows to a 200. The client must not mistake that for a group
        // page with no events box.
        val client = htmlApiClient(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/groups/")) {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/account/login"))
            } else {
                respond("<html><body>please log in</body></html>", HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Text.Html.toString()))
            }
        })
        assertFailsWith<SessionExpiredException> { client.getGroupEvents("quiet-group") }
    }

    @Test
    fun `getGroupEvents throws on a server error response`() = runTest {
        val client = htmlApiClient(MockEngine { _ ->
            respond("oops", HttpStatusCode.InternalServerError)
        })
        assertFailsWith<IllegalStateException> { client.getGroupEvents("quiet-group") }
    }

    @Test
    fun `getEvent scrapes the event page with the session cookie`() = runTest {
        var requestedUrl = ""
        var sentCookie: String? = null
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            sentCookie = request.headers[HttpHeaders.Cookie]
            respond(EVENT_PAGE_HTML_SNIPPET, HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Text.Html.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())

        val detail = client.getEvent("wednesday-hh-at-chainline-39")

        assertEquals("https://www.ravelry.com/events/wednesday-hh-at-chainline-39", requestedUrl)
        assertEquals("sess=test", sentCookie)
        assertEquals("Wednesday HH at Chainline", detail?.title)
        assertEquals("Knitting/crochet group", detail?.eventType)
    }

    @Test
    fun `getEvent returns null for a non-event page`() = runTest {
        val client = routingApiClient { "<html><body>not an event</body></html>" }
        assertEquals(null, client.getEvent("deleted-event"))
    }

    @Test
    fun `getEvent throws SessionExpiredException on 401`() = runTest {
        val client = htmlApiClient(MockEngine { _ ->
            respond("", HttpStatusCode.Unauthorized)
        })
        assertFailsWith<SessionExpiredException> { client.getEvent("some-event") }
    }

    @Test
    fun `getEvent throws ForbiddenException on 403 rather than bouncing to login`() = runTest {
        val client = htmlApiClient(MockEngine { _ ->
            respond("", HttpStatusCode.Forbidden)
        })
        val e = assertFailsWith<ForbiddenException> { client.getEvent("restricted-event") }
        val message = e.message ?: ""
        assertFalse(message.contains("403"))
        assertFalse(message.contains("401"))
    }

    @Test
    fun `getEvent throws SessionExpiredException when redirected to the login page`() = runTest {
        // Same trap as getGroupEvents: an expired cookie 302s to the login page, which
        // Ktor follows to a 200 whose HTML simply has no .event__detail — without the
        // redirect check that would masquerade as "not an event" (null).
        val client = htmlApiClient(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/events/")) {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/account/login"))
            } else {
                respond("<html><body>please log in</body></html>", HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Text.Html.toString()))
            }
        })
        assertFailsWith<SessionExpiredException> { client.getEvent("deleted-event") }
    }

    @Test
    fun `setEventAttendance posts the csrf token as a form with the session cookie`() = runTest {
        var requestedUrl = ""
        var sentCookie: String? = null
        var sentBody = ""
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            sentCookie = request.headers[HttpHeaders.Cookie]
            sentBody = (request.body as io.ktor.client.request.forms.FormDataContent)
                .formData.formUrlEncode()
            respond("R.popover.close();", HttpStatusCode.OK,
                headersOf("Content-Type", "text/javascript"))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())

        val ok = client.setEventAttendance("cozy-meetup", attending = true, csrfToken = "tok+en=")

        assertTrue(ok)
        assertEquals("https://www.ravelry.com/events/cozy-meetup/attend?attending=1", requestedUrl)
        assertEquals("sess=test", sentCookie)
        assertEquals("authenticity_token=tok%2Ben%3D", sentBody)
    }

    @Test
    fun `getEventAttendees scrapes the people page with the session cookie`() = runTest {
        var requestedUrl = ""
        var sentCookie: String? = null
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            sentCookie = request.headers[HttpHeaders.Cookie]
            respond(
                """<div class="event__user_cards"><div class="user_card">
                   <div class="details"><a class="login" href="/people/knitwit">knitwit</a></div>
                   </div></div>""",
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Text.Html.toString()),
            )
        }
        val client = htmlApiClient(engine)

        val attendees = client.getEventAttendees("cozy-meetup")

        assertEquals("https://www.ravelry.com/events/cozy-meetup/people", requestedUrl)
        assertEquals("sess=test", sentCookie)
        assertEquals(listOf("knitwit"), attendees.map { it.username })
    }

    @Test
    fun `getEventAttendees throws SessionExpiredException on 401`() = runTest {
        val client = htmlApiClient(MockEngine { _ ->
            respond("", HttpStatusCode.Unauthorized)
        })
        assertFailsWith<SessionExpiredException> { client.getEventAttendees("cozy-meetup") }
    }

    @Test
    fun `getEventAttendees throws ForbiddenException on 403 rather than bouncing to login`() = runTest {
        val client = htmlApiClient(MockEngine { _ ->
            respond("", HttpStatusCode.Forbidden)
        })
        val e = assertFailsWith<ForbiddenException> { client.getEventAttendees("private-event") }
        val message = e.message ?: ""
        assertFalse(message.contains("403"))
        assertFalse(message.contains("401"))
    }

    @Test
    fun `getSavedEvents scrapes the saved-events page with the session cookie`() = runTest {
        var requestedUrl = ""
        var sentCookie: String? = null
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            sentCookie = request.headers[HttpHeaders.Cookie]
            respond(
                """<div class="event_list" id="event_list">
                   <div class="month">July 2026</div>
                   <div class="event"><div class="date"><div class="day">5th</div></div>
                   <div class="details"><a href="https://www.ravelry.com/events/cozy-meetup" class="title">Cozy Meetup</a></div>
                   </div></div>""",
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Text.Html.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())

        val saved = client.getSavedEvents()

        assertEquals("https://www.ravelry.com/events/saved", requestedUrl)
        assertEquals("sess=test", sentCookie)
        assertEquals(listOf("cozy-meetup"), saved.map { it.permalink })
    }

    @Test
    fun `getSavedEvents throws SessionExpiredException on 401`() = runTest {
        val client = htmlApiClient(MockEngine { _ ->
            respond("", HttpStatusCode.Unauthorized)
        })
        assertFailsWith<SessionExpiredException> { client.getSavedEvents() }
    }

    @Test
    fun `getSavedEvents throws ForbiddenException on 403 rather than bouncing to login`() = runTest {
        val client = htmlApiClient(MockEngine { _ ->
            respond("", HttpStatusCode.Forbidden)
        })
        val e = assertFailsWith<ForbiddenException> { client.getSavedEvents() }
        val message = e.message ?: ""
        assertFalse(message.contains("403"))
        assertFalse(message.contains("401"))
    }

    @Test
    fun `getSavedEvents rejects a redirect to another events page`() = runTest {
        // A session-limited redirect to /events/search renders similar markup that
        // would otherwise parse as a bogus RSVP list — the exact /events/saved
        // prefix must treat it as session expiry, not data.
        val client = htmlApiClient(MockEngine { request ->
            if (request.url.encodedPath == "/events/saved") {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/events/search"))
            } else {
                respond("<div class=\"event_list\"></div>", HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Text.Html.toString()))
            }
        })
        assertFailsWith<SessionExpiredException> { client.getSavedEvents() }
    }

    @Test
    fun `setEventAttendance false posts to unattend and reports rejection`() = runTest {
        var requestedUrl = ""
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            respond("nope", HttpStatusCode.Forbidden,
                headersOf("Content-Type", "text/html"))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())

        val ok = client.setEventAttendance("cozy-meetup", attending = false, csrfToken = "t")

        assertEquals(false, ok)
        assertEquals("https://www.ravelry.com/events/cozy-meetup/unattend", requestedUrl)
    }

    @Test
    fun `getTopicDetail parses the read marker from last_read`() = runTest {
        // Issue #185: the detail's last_read drives the feed's unread count.
        val client = routingApiClient { topicDetailJson(100L, postsCount = 8, lastRead = 3) }
        val topic = client.getTopicDetail(100L)
        assertEquals(8, topic.postsCount)
        assertEquals(3, topic.lastRead)
    }

    @Test
    fun `getTopicDetail defaults last_read to zero when the field is absent`() = runTest {
        val client = routingApiClient { """{"topic":{"id":100,"title":"T"}}""" }
        assertEquals(0, client.getTopicDetail(100L).lastRead)
    }

    @Test
    fun `markTopicRead posts last_read as a form field to the topic read endpoint`() = runTest {
        // Issue #185: viewing a topic advances Ravelry's own read marker so the website
        // and the feed's unread count agree. The marker rides in a form body, mirroring
        // postReply/voteOnPost, and the (empty) response is ignored.
        var capturedPath: String? = null
        var capturedMethod: String? = null
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedMethod = request.method.value
            capturedBody = (request.body as io.ktor.client.request.forms.FormDataContent)
                .formData["last_read"]
            respond("", HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        RavelryApiClient(httpClient, FakeFeedTokenStorage()).markTopicRead(100L, 7)
        assertEquals("/topics/100/read.json", capturedPath)
        assertEquals("POST", capturedMethod)
        assertEquals("7", capturedBody)
    }

    @Test
    fun `postReply raises a cautious error when the response is not the created post`() = runTest {
        // A 200 with a non-JSON body (maintenance page, HTML error) must not be treated
        // as success, and the message must warn the reply may still have posted.
        val client = routingApiClient { path ->
            if (path.contains("/reply.json")) "<html>down for maintenance</html>" else postsJson(1L)
        }
        val failure = runCatching { client.postReply(42L, "hello") }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("the reply may have posted"))
    }

    @Test
    fun `postReply raises a cautious error when valid JSON is missing the forum_post field`() = runTest {
        val client = routingApiClient { path -> if (path.contains("/reply.json")) "{}" else postsJson(1L) }
        val failure = runCatching { client.postReply(42L, "hello") }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("the reply may have posted"))
    }

    @Test
    fun `postReply posts body to reply endpoint and returns created post`() = runTest {
        var capturedPath: String? = null
        var capturedMethod: String? = null
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedMethod = request.method.value
            capturedBody = (request.body as io.ktor.client.request.forms.FormDataContent)
                .formData["body"]
            respond(
                content = replyResponseJson(id = 99L, username = "yarnie"),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val post = RavelryApiClient(httpClient, FakeFeedTokenStorage()).postReply(42L, "My new reply")
        assertEquals("/topics/42/reply.json", capturedPath)
        assertEquals("POST", capturedMethod)
        assertEquals("My new reply", capturedBody)
        assertEquals(99L, post.id)
        assertEquals("yarnie", post.user?.username)
        assertEquals("<p>My new reply</p>", post.bodyHtml)
    }

    @Test
    fun `createTopic posts form fields to create endpoint and returns created topic`() = runTest {
        var capturedPath: String? = null
        var capturedMethod: String? = null
        var capturedForm: io.ktor.http.Parameters? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedMethod = request.method.value
            capturedForm = (request.body as io.ktor.client.request.forms.FormDataContent).formData
            respond(
                content = topicCreateResponseJson(id = 7001L, title = "Show us your WIPs"),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val topic = RavelryApiClient(httpClient, FakeFeedTokenStorage())
            .createTopic(123L, "Show us your WIPs", "Post a photo of what's on your needles!")
        assertEquals("/topics/create.json", capturedPath)
        assertEquals("POST", capturedMethod)
        assertEquals("123", capturedForm!!["forum_id"])
        assertEquals("Show us your WIPs", capturedForm!!["title"])
        assertEquals("Post a photo of what's on your needles!", capturedForm!!["body"])
        // No summary passed → the field is omitted entirely, not sent blank.
        assertEquals(null, capturedForm!!["summary"])
        assertEquals(7001L, topic.id)
        assertEquals("Show us your WIPs", topic.title)
        assertEquals("yarnie", topic.createdByUser?.username)
    }

    @Test
    fun `createTopic sends the summary form field when one is provided`() = runTest {
        var capturedForm: io.ktor.http.Parameters? = null
        val engine = MockEngine { request ->
            capturedForm = (request.body as io.ktor.client.request.forms.FormDataContent).formData
            respond(
                content = topicCreateResponseJson(id = 7002L, title = "Show us your WIPs"),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        RavelryApiClient(httpClient, FakeFeedTokenStorage())
            .createTopic(123L, "Show us your WIPs", "Photos please!", "A weekly photo thread")
        assertEquals("A weekly photo thread", capturedForm!!["summary"])
    }

    @Test
    fun `createTopic omits a blank summary`() = runTest {
        var capturedForm: io.ktor.http.Parameters? = null
        val engine = MockEngine { request ->
            capturedForm = (request.body as io.ktor.client.request.forms.FormDataContent).formData
            respond(
                content = topicCreateResponseJson(id = 7003L, title = "Show us your WIPs"),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        RavelryApiClient(httpClient, FakeFeedTokenStorage())
            .createTopic(123L, "Show us your WIPs", "Photos please!", "   ")
        assertEquals(null, capturedForm!!["summary"])
    }

    @Test
    fun `createTopic raises a cautious error when the response is not the created topic`() = runTest {
        // A 200 with a non-JSON body (maintenance page, HTML error) must not be treated
        // as success, and the message must warn the topic may still have been created.
        val client = routingApiClient { path ->
            if (path.contains("/topics/create.json")) "<html>down for maintenance</html>" else postsJson(1L)
        }
        val failure = runCatching { client.createTopic(123L, "title", "body") }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("the topic may have been created"))
    }

    @Test
    fun `createTopic raises a cautious error when valid JSON is missing the topic field`() = runTest {
        val client = routingApiClient { path -> if (path.contains("/topics/create.json")) "{}" else postsJson(1L) }
        val failure = runCatching { client.createTopic(123L, "title", "body") }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("the topic may have been created"))
    }

    @Test
    fun `deletePost fetches csrf token then posts _method=delete override`() = runTest {
        data class Captured(val method: String, val path: String, val form: io.ktor.http.Parameters?)
        val requests = mutableListOf<Captured>()
        val engine = MockEngine { request ->
            val form = (request.body as? io.ktor.client.request.forms.FormDataContent)?.formData
            requests += Captured(request.method.value, request.url.encodedPath, form)
            // The token page is the GET; the second request is the delete POST.
            val content = if (request.method.value == "POST") "ok" else TOKEN_PAGE_HTML
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/html"),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        RavelryApiClient(httpClient, FakeFeedTokenStorage()).deletePost(555L)
        assertEquals(2, requests.size)
        assertEquals("GET", requests[0].method)
        assertEquals("POST", requests[1].method)
        assertEquals("/forum_posts/555", requests[1].path)
        assertEquals("delete", requests[1].form?.get("_method"))
        assertEquals("tok-abc123", requests[1].form?.get("authenticity_token"))
    }

    @Test
    fun `deletePost fails when no csrf token on page`() = runTest {
        val client = routingApiClient { "<html><body>no token here</body></html>" }
        val result = runCatching { client.deletePost(555L) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `deletePost fails on rejected delete`() = runTest {
        // Not a 403 — that's now classified as ForbiddenException (see the dedicated
        // test below). This covers the generic rejection branch: an unexpected non-2xx/
        // non-3xx status that isn't a permission denial either.
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond(content = "nope", status = HttpStatusCode.InternalServerError)
            } else {
                respond(
                    content = TOKEN_PAGE_HTML,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "text/html"),
                )
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val result = runCatching { RavelryApiClient(httpClient, FakeFeedTokenStorage()).deletePost(555L) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `deletePost fails on an unexpected informational status`() = runTest {
        // The rejected-delete test above is >= 400 (fails the "in 300..399" upper bound);
        // this is < 300 and non-2xx (fails its lower bound) — a distinct path through
        // the success check even though both ultimately land in the same else branch.
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond(content = "", status = HttpStatusCode(100, "Continue"))
            } else {
                respond(
                    content = TOKEN_PAGE_HTML,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "text/html"),
                )
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val result = runCatching { RavelryApiClient(httpClient, FakeFeedTokenStorage()).deletePost(555L) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `deletePost throws SessionExpiredException when the delete redirects to login`() = runTest {
        // Ktor doesn't follow redirects for POST: a 302 is how BOTH outcomes look.
        // A Location pointing at the login page means the session expired — treating
        // it as success would remove the post locally while it lives on at Ravelry.
        // Bare "/login", not "/account/login" — that's a separate case (below), and the
        // two must not collapse into testing only one of startsWith("/login")'s two sides.
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/login"))
            } else {
                respond(TOKEN_PAGE_HTML, HttpStatusCode.OK,
                    headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        assertFailsWith<SessionExpiredException> {
            RavelryApiClient(httpClient, FakeFeedTokenStorage()).deletePost(555L)
        }
    }

    @Test
    fun `deletePost throws SessionExpiredException when the redirect is account slash login`() = runTest {
        // Regression: the original version of this test used this exact Location but
        // asserted it exercised startsWith("/login") — it actually only ever exercised
        // startsWith("/account"), since "/account/login" starts with "/account", not
        // "/login". Kept as its own case so both real-world redirect shapes stay covered.
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/account/login"))
            } else {
                respond(TOKEN_PAGE_HTML, HttpStatusCode.OK,
                    headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        assertFailsWith<SessionExpiredException> {
            RavelryApiClient(httpClient, FakeFeedTokenStorage()).deletePost(555L)
        }
    }

    @Test
    fun `deletePost throws SessionExpiredException when the redirect names account but not login`() = runTest {
        // Covers the other half of the redirectPath.startsWith("/login") ||
        // startsWith("/account") check — Ravelry's own account-locked/logged-out
        // redirects don't all say "login".
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/account"))
            } else {
                respond(TOKEN_PAGE_HTML, HttpStatusCode.OK,
                    headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        assertFailsWith<SessionExpiredException> {
            RavelryApiClient(httpClient, FakeFeedTokenStorage()).deletePost(555L)
        }
    }

    @Test
    fun `joinGroup fetches the csrf token then PUT-overrides to the group join endpoint`() = runTest {
        data class Captured(val method: String, val path: String, val form: io.ktor.http.Parameters?)
        val requests = mutableListOf<Captured>()
        val engine = MockEngine { request ->
            val form = (request.body as? io.ktor.client.request.forms.FormDataContent)?.formData
            requests += Captured(request.method.value, request.url.encodedPath, form)
            // The token page is the GET; the join is the POST (Prototype tunnels PUT as POST).
            val content = if (request.method.value == "POST") "ok" else TOKEN_PAGE_HTML
            respond(content, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        RavelryApiClient(httpClient, FakeFeedTokenStorage()).joinGroup("fibersocial-app-support")
        assertEquals(2, requests.size)
        assertEquals("GET", requests[0].method)
        assertEquals("POST", requests[1].method)
        assertEquals("/groups/fibersocial-app-support/join", requests[1].path)
        assertEquals("put", requests[1].form?.get("_method"))
        assertEquals("tok-abc123", requests[1].form?.get("authenticity_token"))
    }

    @Test
    fun `joinGroup throws ForbiddenException on 403`() = runTest {
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond("", HttpStatusCode.Forbidden)
            } else {
                respond(TOKEN_PAGE_HTML, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        assertFailsWith<ForbiddenException> {
            RavelryApiClient(httpClient, FakeFeedTokenStorage()).joinGroup("some-group")
        }
    }

    @Test
    fun `joinGroup throws SessionExpiredException when the join redirects to login`() = runTest {
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/account/login"))
            } else {
                respond(TOKEN_PAGE_HTML, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        assertFailsWith<SessionExpiredException> {
            RavelryApiClient(httpClient, FakeFeedTokenStorage()).joinGroup("some-group")
        }
    }

    @Test
    fun `joinGroup throws SessionExpiredException when redirected straight to the login path`() = runTest {
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/login"))
            } else {
                respond(TOKEN_PAGE_HTML, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        assertFailsWith<SessionExpiredException> {
            RavelryApiClient(httpClient, FakeFeedTokenStorage()).joinGroup("some-group")
        }
    }

    @Test
    fun `joinGroup succeeds on a non-login redirect such as permalink canonicalization`() = runTest {
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/groups/fibersocial-app-support"))
            } else {
                respond(TOKEN_PAGE_HTML, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        // Must not throw.
        RavelryApiClient(httpClient, FakeFeedTokenStorage()).joinGroup("app-support")
    }

    @Test
    fun `joinGroup throws on an unexpected rejection status`() = runTest {
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond("", HttpStatusCode.InternalServerError)
            } else {
                respond(TOKEN_PAGE_HTML, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        assertFailsWith<IllegalStateException> {
            RavelryApiClient(httpClient, FakeFeedTokenStorage()).joinGroup("some-group")
        }
    }

    @Test
    fun `deletePost succeeds when the redirect is to a group whose slug merely contains account or login`() = runTest {
        // Regression: a raw location.contains("/login")/contains("/account") substring
        // check would misfire on a real, successful-delete redirect whose path merely
        // starts with a group named "login-fanatics" — a false session-expiry that
        // would leave the (actually-deleted) post shown locally as still present.
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/groups/login-fanatics/discuss/1234"))
            } else {
                respond(TOKEN_PAGE_HTML, HttpStatusCode.OK,
                    headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        // Must not throw.
        RavelryApiClient(httpClient, FakeFeedTokenStorage()).deletePost(555L)
    }

    @Test
    fun `deletePost throws ForbiddenException on a 403 matching editPost's classification`() = runTest {
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond("nope", HttpStatusCode.Forbidden)
            } else {
                respond(TOKEN_PAGE_HTML, HttpStatusCode.OK,
                    headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        assertFailsWith<com.autom8ed.fibersocial.auth.ForbiddenException> {
            RavelryApiClient(httpClient, FakeFeedTokenStorage()).deletePost(555L)
        }
    }

    @Test
    fun `deletePost succeeds when the delete redirects back to the topic`() = runTest {
        // The "ok" 200 in the fetch/post test above isn't actually what Ravelry sends on a
        // real successful delete — per this method's own comment, Ktor doesn't follow
        // redirects for POST, so a genuine success also surfaces as a 3xx. This covers
        // that half of `response.status.isSuccess() || response.status.value in 300..399`,
        // distinct from a login/account redirect (session expiry) or a 2xx body.
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/discuss/some-group/1234"))
            } else {
                respond(TOKEN_PAGE_HTML, HttpStatusCode.OK,
                    headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        // Must not throw.
        RavelryApiClient(httpClient, FakeFeedTokenStorage()).deletePost(555L)
    }

    @Test
    fun `deletePost reuses the cached csrf token across deletes`() = runTest {
        var tokenPageFetches = 0
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                respond("ok", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            } else {
                tokenPageFetches++
                respond(TOKEN_PAGE_HTML, HttpStatusCode.OK,
                    headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())
        client.deletePost(1L)
        client.deletePost(2L)
        // The token is session-stable; the homepage must not be re-downloaded per delete.
        assertEquals(1, tokenPageFetches)
    }

    @Test
    fun `csrf token extraction tolerates attribute order and the id-only form`() = runTest {
        // Real Ravelry pages put content before id/name; extraction must not depend
        // on attribute order (the old regex did).
        val html = """<html><head>
            <meta content="tok-xyz" id="authenticity-token" name="authenticity-token">
            </head><body></body></html>"""
        var sentToken: String? = null
        val engine = MockEngine { request ->
            if (request.method.value == "POST") {
                sentToken = (request.body as io.ktor.client.request.forms.FormDataContent)
                    .formData["authenticity_token"]
                respond("ok", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            } else {
                respond(html, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        RavelryApiClient(httpClient, FakeFeedTokenStorage()).deletePost(555L)
        assertEquals("tok-xyz", sentToken)
    }

    @Test
    fun `editPost posts body to forum_posts endpoint and returns updated post`() = runTest {
        var capturedPath: String? = null
        var capturedMethod: String? = null
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedMethod = request.method.value
            capturedBody = (request.body as io.ktor.client.request.forms.FormDataContent)
                .formData["body"]
            respond(
                content = forumPostJson(id = 7L, body = "edited body", bodyHtml = "<p>edited body</p>"),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val post = RavelryApiClient(httpClient, FakeFeedTokenStorage()).editPost(7L, "edited body")
        assertEquals("/forum_posts/7.json", capturedPath)
        assertEquals("POST", capturedMethod)
        assertEquals("edited body", capturedBody)
        assertEquals("edited body", post.body)
        assertEquals("<p>edited body</p>", post.bodyHtml)
    }

    @Test
    fun `editPost raises a cautious error when the response is not the updated post`() = runTest {
        // Same reasoning as postReply/createTopic: a 200 with a non-JSON body must not be
        // treated as success, and the message must warn the edit may still have applied.
        val client = routingApiClient { "<html>down for maintenance</html>" }
        val failure = runCatching { client.editPost(7L, "edited body") }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("the edit may have applied"))
    }

    @Test
    fun `editPost raises a cautious error when valid JSON is missing the forum_post field`() = runTest {
        val client = routingApiClient { "{}" }
        val failure = runCatching { client.editPost(7L, "edited body") }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("the edit may have applied"))
    }

    @Test
    fun `postReply parses a null editable field as unknown rather than a failure`() = runTest {
        // Ravelry returns "editable": null on a freshly created reply. Post.editable is
        // nullable so this parses to null ("unknown"), which the edit UI treats optimistically
        // as editable — see Post.editable / issue #82.
        val client = routingApiClient {
            """{"forum_post":{"id":5,"body_html":"<p>hi</p>","body":"hi","editable":null,"user":{"username":"me"}}}"""
        }
        val post = client.postReply(42L, "hi")
        assertEquals(5L, post.id)
        assertEquals(null, post.editable)
    }

    /**
     * MockEngine wired for the three-request upload flow. Captures each request so the
     * tests can assert on auth headers and bodies per endpoint.
     */
    private fun uploadApiClient(
        uploadsJson: String = """{"uploads":{"file0":{"image_id":16}}}""",
        tokenStatus: HttpStatusCode = HttpStatusCode.OK,
        uploadStatus: HttpStatusCode = HttpStatusCode.OK,
        attachmentStatus: HttpStatusCode = HttpStatusCode.OK,
        attachmentBody: String = """{"image_path":"/attached/yarnie/16.jpg"}""",
        capture: MutableMap<String, HttpRequestData> = mutableMapOf(),
    ): RavelryApiClient {
        val engine = MockEngine { request ->
            capture[request.url.encodedPath] = request
            when (request.url.encodedPath) {
                "/upload/request_token.json" -> respond(
                    """{"upload_token":"tok-abc"}""", tokenStatus,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/upload/image.json" -> respond(
                    uploadsJson, uploadStatus,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                "/extras/create_attachment.json" -> respond(
                    attachmentBody, attachmentStatus,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return RavelryApiClient(httpClient, FakeFeedTokenStorage())
    }

    @Test
    fun `uploadForumImage chains token then multipart upload then attachment`() = runTest {
        val capture = mutableMapOf<String, HttpRequestData>()
        val client = uploadApiClient(capture = capture)
        val imagePath = client.uploadForumImage("photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        assertEquals("/attached/yarnie/16.jpg", imagePath)

        val multipart = capture.getValue("/upload/image.json").body.toByteArray().decodeToString()
        assertTrue("tok-abc" in multipart, "upload_token missing from multipart body")
        assertTrue("name=file0" in multipart || "name=\"file0\"" in multipart, "file0 part missing")
        assertTrue("filename=\"photo.jpg\"" in multipart, "filename missing")

        val attachmentBody = capture.getValue("/extras/create_attachment.json").body.toByteArray().decodeToString()
        assertEquals("image_id=16", attachmentBody)
    }

    @Test
    fun `uploadForumImage authenticates token and attachment requests but not the upload itself`() = runTest {
        val capture = mutableMapOf<String, HttpRequestData>()
        uploadApiClient(capture = capture).uploadForumImage("p.png", "image/png", byteArrayOf(1))
        assertEquals(
            "Bearer test-token",
            capture.getValue("/upload/request_token.json").headers[HttpHeaders.Authorization],
        )
        assertEquals(
            "Bearer test-token",
            capture.getValue("/extras/create_attachment.json").headers[HttpHeaders.Authorization],
        )
        // Docs: upload/image is NOT an authenticated method — the one-time token authorizes it.
        assertEquals(null, capture.getValue("/upload/image.json").headers[HttpHeaders.Authorization])
    }

    @Test
    fun `uploadForumImage accepts the docs example array-shaped uploads response`() = runTest {
        val client = uploadApiClient(uploadsJson = """{"uploads":[{"file0":{"image_id":16}}]}""")
        assertEquals("/attached/yarnie/16.jpg", client.uploadForumImage("p.jpg", "image/jpeg", byteArrayOf(1)))
    }

    @Test
    fun `uploadForumImage fails loudly when the upload response has no image_id`() = runTest {
        val client = uploadApiClient(uploadsJson = """{"uploads":{}}""")
        assertFailsWith<IllegalStateException> {
            client.uploadForumImage("p.jpg", "image/jpeg", byteArrayOf(1))
        }
    }

    @Test
    fun `uploadForumImage surfaces a 403 on create_attachment as ForbiddenException`() = runTest {
        val client = uploadApiClient(attachmentStatus = HttpStatusCode.Forbidden)
        assertFailsWith<ForbiddenException> {
            client.uploadForumImage("p.jpg", "image/jpeg", byteArrayOf(1))
        }
    }

    @Test
    fun `uploadForumImage strips quotes from the multipart filename`() = runTest {
        val capture = mutableMapOf<String, HttpRequestData>()
        uploadApiClient(capture = capture).uploadForumImage("""my "best" photo.jpg""", "image/jpeg", byteArrayOf(1))
        val multipart = capture.getValue("/upload/image.json").body.toByteArray().decodeToString()
        assertTrue("filename=\"my best photo.jpg\"" in multipart, "quotes not stripped: $multipart")
    }

    @Test
    fun `uploadForumImage strips control characters and backslashes from the filename`() = runTest {
        // A hostile provider's DISPLAY_NAME with CR/LF would make Ktor's header
        // validation throw before any request is made; backslashes would escape the
        // quoted header parameter.
        val capture = mutableMapOf<String, HttpRequestData>()
        uploadApiClient(capture = capture).uploadForumImage("bad\r\nname\\photo.jpg", "image/jpeg", byteArrayOf(1))
        val multipart = capture.getValue("/upload/image.json").body.toByteArray().decodeToString()
        assertTrue("filename=\"badnamephoto.jpg\"" in multipart, "control chars not stripped: $multipart")
    }

    @Test
    fun `uploadForumImage does not report a request_token 403 as ForbiddenException`() = runTest {
        // Only create_attachment's 403 means "no Extras subscription"; a 403 on the
        // token request must not be mapped to the Extras upsell message by callers.
        val client = uploadApiClient(tokenStatus = HttpStatusCode.Forbidden)
        val e = assertFailsWith<IllegalStateException> {
            client.uploadForumImage("p.jpg", "image/jpeg", byteArrayOf(1))
        }
        assertTrue("upload token" in e.message.orEmpty(), "unexpected message: ${e.message}")
    }

    @Test
    fun `uploadForumImage turns a garbage create_attachment body into a readable error`() = runTest {
        // authenticatedRequest passes non-401/403 error bodies through; the decode
        // failure must not leak raw SerializationException text into the UI.
        val client = uploadApiClient(
            attachmentStatus = HttpStatusCode.InternalServerError,
            attachmentBody = "<html>Server Error</html>",
        )
        val e = assertFailsWith<IllegalStateException> {
            client.uploadForumImage("p.jpg", "image/jpeg", byteArrayOf(1))
        }
        assertTrue("Ravelry" in e.message.orEmpty(), "unexpected message: ${e.message}")
    }

    @Test
    fun `uploadForumImage fails loudly when the upload POST is rejected`() = runTest {
        val client = uploadApiClient(uploadStatus = HttpStatusCode.PayloadTooLarge)
        val e = assertFailsWith<IllegalStateException> {
            client.uploadForumImage("p.jpg", "image/jpeg", byteArrayOf(1))
        }
        assertTrue("413" in (e.message ?: ""), "expected status in message: ${e.message}")
    }

    @Test
    fun `uploadForumImage fails loudly on a malformed upload response`() = runTest {
        val client = uploadApiClient(uploadsJson = "not json at all")
        assertFailsWith<IllegalStateException> {
            client.uploadForumImage("p.jpg", "image/jpeg", byteArrayOf(1))
        }
    }

    @Test
    fun `uploadForumImage fails loudly when uploads is neither object nor array`() = runTest {
        val client = uploadApiClient(uploadsJson = """{"uploads":"nope"}""")
        assertFailsWith<IllegalStateException> {
            client.uploadForumImage("p.jpg", "image/jpeg", byteArrayOf(1))
        }
    }

    @Test
    fun `getProjects hits the list endpoint sorted newest-first and parses summaries`() = runTest {
        var captured: io.ktor.http.Url? = null
        val client = routingApiClientCapturing(onRequest = { captured = it }) {
            """{"projects":[
                {"id":1,"name":"Autumn Socks","permalink":"autumn-socks",
                 "first_photo":{"id":901,"square_url":"https://img.example/sq.jpg"},"photos_count":2}
            ]}"""
        }
        val projects = client.getProjects("yarnie")
        assertEquals("/projects/yarnie/list.json", captured?.encodedPath)
        // "created_" (trailing underscore) is Ravelry's descending sort — newest first.
        // Plain "created" would return oldest first, burying recent projects.
        assertEquals("created_", captured?.parameters?.get("sort"))
        assertEquals(1, projects.size)
        assertEquals("Autumn Socks", projects[0].name)
        assertEquals("https://img.example/sq.jpg", projects[0].firstPhoto?.squareUrl)
        assertEquals(2, projects[0].photosCount)
    }

    @Test
    fun `getProjects defaults to empty when the response omits the projects field`() = runTest {
        val client = routingApiClient { "{}" }
        assertEquals(emptyList(), client.getProjects("yarnie"))
    }

    @Test
    fun `getProjectPhotos returns the project detail's photos`() = runTest {
        var captured: io.ktor.http.Url? = null
        val client = routingApiClientCapturing(onRequest = { captured = it }) {
            """{"project":{"id":7,"name":"Autumn Socks","photos":[
                {"id":901,"medium_url":"https://img.example/m1.jpg"},
                {"id":902,"medium_url":"https://img.example/m2.jpg"}
            ]}}"""
        }
        val photos = client.getProjectPhotos("yarnie", 7L)
        assertEquals("/projects/yarnie/7.json", captured?.encodedPath)
        assertEquals(listOf(901L, 902L), photos.map { it.id })
    }

    @Test
    fun `getProjectDetail hits the detail endpoint by permalink and parses the fields`() = runTest {
        var captured: io.ktor.http.Url? = null
        val client = routingApiClientCapturing(onRequest = { captured = it }) {
            """{"project":{"id":7,"name":"Autumn Socks","permalink":"autumn-socks",
                "pattern_name":"Vanilla Socks","status_name":"In progress","progress":60,
                "craft_name":"Knitting","started":"2026/06/01","made_for":"Mom","size":"M",
                "notes":"So *cozy*","notes_html":"<p>So <em>cozy</em></p>","tag_names":["socks","gift"],
                "photos":[{"id":901,"medium_url":"https://img.example/m1.jpg"}]}}"""
        }
        val project = client.getProjectDetail("yarnie", "autumn-socks")
        assertEquals("/projects/yarnie/autumn-socks.json", captured?.encodedPath)
        assertEquals("Autumn Socks", project.name)
        assertEquals("Vanilla Socks", project.patternName)
        assertEquals("In progress", project.statusName)
        assertEquals(60, project.progress)
        assertEquals("Knitting", project.craftName)
        assertEquals("Mom", project.madeFor)
        assertEquals("So *cozy*", project.notes)
        assertEquals(listOf("socks", "gift"), project.tagNames)
        assertEquals(listOf(901L), project.photos.map { it.id })
    }

    @Test
    fun `getProjectDetail fails loudly when the response is missing the project`() = runTest {
        val client = routingApiClient { "{}" }
        assertFailsWith<Exception> { client.getProjectDetail("yarnie", "autumn-socks") }
    }

    @Test
    fun `getProjectDetail parses the full field set`() = runTest {
        val client = routingApiClient {
            """{"project":{"id":7,"name":"Autumn Socks","permalink":"autumn-socks",
                "pattern_name":"Vanilla Socks","pattern_id":42,"status_name":"In progress",
                "craft_name":"Knitting","progress":60,"started":"2026/06/01","completed":"2026/06/20",
                "made_for":"Mom","size":"M","notes":"So *cozy*","notes_html":"<p>So <em>cozy</em></p>",
                "tag_names":["socks","gift"],
                "photos":[{"id":901,"medium_url":"https://img.example/m1.jpg"}]}}"""
        }
        val p = client.getProjectDetail("yarnie", "autumn-socks")
        assertEquals(42L, p.patternId)
        assertEquals("In progress", p.statusName)
        assertEquals(60, p.progress)
        assertEquals("2026/06/01", p.started)
        assertEquals("2026/06/20", p.completed)
        assertEquals("Mom", p.madeFor)
        assertEquals("M", p.size)
        assertEquals(listOf("socks", "gift"), p.tagNames)
        assertEquals("So *cozy*", p.notes)
        assertEquals(listOf(901L), p.photos.map { it.id })
    }

    @Test
    fun `getProjectComments hits the comments endpoint and parses users`() = runTest {
        var captured: io.ktor.http.Url? = null
        val client = routingApiClientCapturing(onRequest = { captured = it }) {
            """{"comments":[
                {"id":1,"comment_html":"<p>lovely</p>","created_at":"2026-01-01T00:00:00Z",
                 "user":{"username":"fan","small_photo_url":"https://img/f.jpg"}},
                {"id":2,"comment_html":"<p>thanks</p>"}
            ]}"""
        }
        val comments = client.getProjectComments("yarnie", 7L)
        assertEquals("/projects/yarnie/7/comments.json", captured?.encodedPath)
        // Ask for the max page (Ravelry defaults to 25) so short threads aren't truncated.
        assertEquals("100", captured?.parameters?.get("page_size"))
        assertEquals(listOf(1L, 2L), comments.map { it.id })
        assertEquals("fan", comments[0].user?.username)
        assertEquals("https://img/f.jpg", comments[0].user?.avatarUrl)
        assertEquals(null, comments[1].user)
    }

    @Test
    fun `getProjectComments defaults to empty when the field is absent`() = runTest {
        assertEquals(emptyList(), routingApiClient { "{}" }.getProjectComments("yarnie", 7L))
    }

    @Test
    fun `postProjectComment posts the project type and id and parses the created comment`() = runTest {
        var body: String? = null
        val engine = MockEngine { request ->
            body = (request.body as io.ktor.client.request.forms.FormDataContent).formData.formUrlEncode()
            respond(
                """{"comment":{"id":99,"comment_html":"<p>mine</p>","user":{"username":"me"}}}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }.let { RavelryApiClient(it, FakeFeedTokenStorage()) }
        val comment = client.postProjectComment(7L, "mine")
        assertEquals(99L, comment.id)
        assertTrue(body!!.contains("type=project"), body)
        assertTrue(body!!.contains("commented_id=7"), body)
        assertTrue(body!!.contains("body=mine"), body)
    }

    @Test
    fun `postProjectComment surfaces a garbage response as a readable error`() = runTest {
        val client = routingApiClient { "<html>nope</html>" }
        val e = assertFailsWith<IllegalStateException> { client.postProjectComment(7L, "hi") }
        assertTrue("Ravelry" in e.message.orEmpty(), e.message ?: "")
    }

    @Test
    fun `deleteComment issues a DELETE to the comment endpoint`() = runTest {
        var method: io.ktor.http.HttpMethod? = null
        var path: String? = null
        val engine = MockEngine { request ->
            method = request.method
            path = request.url.encodedPath
            respond("", HttpStatusCode.OK, headersOf())
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }.let { RavelryApiClient(it, FakeFeedTokenStorage()) }
        client.deleteComment(55L)
        assertEquals(io.ktor.http.HttpMethod.Delete, method)
        assertEquals("/comments/55.json", path)
    }

    @Test
    fun `deleteComment maps a 403 to ForbiddenException`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Forbidden, headersOf()) }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }.let { RavelryApiClient(it, FakeFeedTokenStorage()) }
        assertFailsWith<ForbiddenException> { client.deleteComment(55L) }
    }

    @Test
    fun `getPatternInfo parses permalink and author and accepts the patterns key too`() = runTest {
        var captured: io.ktor.http.Url? = null
        val client = routingApiClientCapturing(onRequest = { captured = it }) {
            """{"pattern":{"id":42,"name":"Vanilla Socks","permalink":"vanilla-socks",
                "pattern_author":{"id":3,"name":"Jane","permalink":"jane"}}}"""
        }
        val info = client.getPatternInfo(42L)
        assertEquals("/patterns/42.json", captured?.encodedPath)
        assertEquals("Vanilla Socks", info.name)
        assertEquals("vanilla-socks", info.permalink)
        assertEquals("Jane", info.author?.name)
        assertEquals("https://www.ravelry.com/patterns/library/vanilla-socks", info.webUrl)

        // Some doc revisions nest under "patterns" — accepted as a fallback.
        val alt = routingApiClient { """{"patterns":{"id":42,"name":"Alt","permalink":"alt"}}""" }
        assertEquals("Alt", alt.getPatternInfo(42L).name)
    }

    @Test
    fun `getPatternInfo fails loudly when neither key is present`() = runTest {
        assertFailsWith<Exception> { routingApiClient { "{}" }.getPatternInfo(42L) }
    }

    @Test
    fun `getProjectPhotos defaults to empty when the project has no photos field`() = runTest {
        val client = routingApiClient { """{"project":{"id":7,"name":"Autumn Socks"}}""" }
        assertEquals(emptyList(), client.getProjectPhotos("yarnie", 7L))
    }

    @Test
    fun `getProjects fails loudly when a project is missing its id`() = runTest {
        val client = routingApiClient { """{"projects":[{}]}""" }
        assertFailsWith<Exception> { client.getProjects("yarnie") }
    }

    @Test
    fun `getProjectPhotos fails loudly when the response is missing the project`() = runTest {
        val client = routingApiClient { "{}" }
        assertFailsWith<Exception> { client.getProjectPhotos("yarnie", 7L) }
    }

    @Test
    fun `uploadForumImage fails loudly when the token response is malformed`() = runTest {
        val client = routingApiClient { path ->
            if (path == "/upload/request_token.json") "{}" else error("should not get past the token step")
        }
        assertFailsWith<Exception> { client.uploadForumImage("p.jpg", "image/jpeg", byteArrayOf(1)) }
    }

    @Test
    fun `uploadForumImage fails loudly when the attachment response is malformed`() = runTest {
        val client = routingApiClient { path ->
            when (path) {
                "/upload/request_token.json" -> """{"upload_token":"tok"}"""
                "/upload/image.json" -> """{"uploads":{"file0":{"image_id":7}}}"""
                else -> "{}"
            }
        }
        assertFailsWith<Exception> { client.uploadForumImage("p.jpg", "image/jpeg", byteArrayOf(1)) }
    }
}

private fun htmlApiClient(engine: MockEngine): RavelryApiClient {
    val httpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return RavelryApiClient(httpClient, FakeFeedTokenStorage())
}

private val EVENT_PAGE_HTML_SNIPPET = """
<div class="page_title"><div class="page_title__supertitle"><a href="https://www.ravelry.com/events">events</a></div>
Wednesday HH at Chainline
</div>
<div class="event__detail">
<div class="event__type">Knitting/crochet group</div>
<div class="event__dates">July  1, 2026 @  5:30 PM</div>
</div>
"""

private val GROUP_PAGE_EVENTS_HTML = """
<div class="box" id="upcoming_events"><div id="events">
<div class="event">
<div class="what"><a href="https://www.ravelry.com/events/sunday-circle-at-postdoc-brewing-10">Sunday Circle at Postdoc Brewing</a></div>
<div class="when">July  5, 2026 @ 1:00 PM</div>
<div class="who"><a href="https://www.ravelry.com/events/sunday-circle-at-postdoc-brewing-10/people">1 person</a></div>
</div>
<div class="event">
<div class="what"><a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-39">Wednesday HH at Chainline</a></div>
<div class="when">July  8, 2026 @ 5:30 PM</div>
<div class="who"><a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-39/people">0 people</a></div>
</div>
</div></div>
"""
